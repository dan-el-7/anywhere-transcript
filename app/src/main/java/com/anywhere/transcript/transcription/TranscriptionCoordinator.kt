package com.anywhere.transcript.transcription

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.anywhere.transcript.audio.AudioDecoder
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.ModelInfo
import com.anywhere.transcript.data.ModelRepository
import com.anywhere.transcript.data.SettingsRepository
import com.anywhere.transcript.data.db.HistoryEntry
import com.anywhere.transcript.engine.BackendDevice
import com.anywhere.transcript.engine.QnnWhisperEngine
import com.anywhere.transcript.engine.TranscriptionCallback
import com.anywhere.transcript.engine.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs the full pipeline for one file: pick model/backend → open decoder →
 * windowed transcription (streaming partials, cancellable) → history.
 * Two engines: whisper.cpp (v1, CPU/OpenCL/Hexagon-ggml) and QNN turbo (v2, NPU).
 */
class TranscriptionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val modelRepo: ModelRepository,
    private val historyDao: com.anywhere.transcript.data.db.HistoryDao,
) {
    private var job: Job? = null
    private var backendOverride: String? = null

    fun start(uri: Uri, displayName: String, backendOverride: String? = null) {
        if (job?.isActive == true) return
        this.backendOverride = backendOverride
        job = scope.launch { run(uri, displayName) }
    }

    fun cancel() {
        TranscriptionBus.requestCancel()
    }

    private suspend fun run(uri: Uri, displayName: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val bus = TranscriptionBus
        bus.reset()
        try {
            val s = settingsRepo.current()
            val tier = DeviceTier.fromNameOrNull(s.tierOverride) ?: detectTier()
            val useQnn = backendOverride == "qnn" || (backendOverride == null && s.backendPref == "qnn")

            var model: ModelInfo? = null
            var modelLabel: String
            var modelId: String
            if (useQnn) {
                if (!QnnWhisperEngine.modelsReady(context)) {
                    bus.update {
                        it.copy(
                            phase = JobPhase.ERROR,
                            fileName = displayName,
                            error = "QNN model files aren't in the app's files/qnn folder yet.",
                            modelMissing = true,
                        )
                    }
                    return
                }
                modelId = "qnn-turbo-v79"
                modelLabel = "Large-V3-Turbo QNN"
            } else {
                model = modelRepo.selectedOrDefault(s, tier)
                if (!modelRepo.isDownloaded(model.id)) {
                    bus.update {
                        it.copy(
                            phase = JobPhase.ERROR,
                            fileName = displayName,
                            error = "Model “${model.label}” isn't downloaded yet. Get it from the Models tab.",
                            modelMissing = true,
                        )
                    }
                    return
                }
                modelId = model.id
                modelLabel = model.label
            }

            val backend = if (useQnn) null else pickBackend(s.backendPref, model!!)
            bus.update {
                it.copy(
                    phase = JobPhase.PREPARING,
                    fileName = displayName,
                    modelId = modelId,
                    modelLabel = modelLabel,
                    backend = backend?.name ?: "QNN",
                )
            }
            Log.i(TAG, "job start: model=$modelId backend=${backend?.name ?: "QNN"} pref=${s.backendPref} tier=$tier")

            val ctx = if (useQnn) 0L else run {
                val c = WhisperEngine.createContext(
                    modelRepo.modelFile(model!!.id).absolutePath,
                    backend!!.name,
                    WhisperEngine.defaultThreads(),
                )
                if (c == 0L) {
                    bus.update {
                        it.copy(phase = JobPhase.ERROR, error = "Could not load model “$modelLabel”.")
                    }
                }
                c
            }
            if (!useQnn && ctx == 0L) return
            if (useQnn && !QnnWhisperEngine.initIfNeeded(context)) {
                bus.update {
                    it.copy(phase = JobPhase.ERROR, error = "QNN init failed — see logcat (tag QnnWhisper).")
                }
                return
            }

            val decoder = AudioDecoder(context.applicationContext)
            val segments = mutableListOf<TranscriptSegment>()
            val text = StringBuilder()
            var audioMs = 0L
            try {
                val opened = decoder.open(uri)
                audioMs = maxOf(opened.durationMs, 0L)
                bus.update { it.copy(phase = JobPhase.DECODING, progress = -1f) }

                val windowSec = when {
                    useQnn -> 30
                    tier == DeviceTier.LOW -> 60
                    tier == DeviceTier.MID -> 120
                    else -> 300
                }
                val totalMs = opened.durationMs
                var windowStartMs = 0L

                while (true) {
                    if (TranscriptionBus.cancelRequested) break
                    val window = decoder.nextWindow(windowSec) { TranscriptionBus.cancelRequested } ?: break
                    val windowMs = window.size / 16L
                    bus.update { it.copy(phase = JobPhase.TRANSCRIBING) }

                    if (useQnn) {
                        val windowText = QnnWhisperEngine.transcribeWindow(
                            context,
                            window,
                            s.language.ifBlank { "en" },
                        )
                        if (TranscriptionBus.cancelRequested) break
                        if (windowText.isNotEmpty()) {
                            segments.add(TranscriptSegment(windowStartMs, windowStartMs + windowMs, windowText))
                            text.append(windowText).append(' ')
                            bus.update { it.copy(partialText = text.toString().trim()) }
                        }
                        val peak = window.maxOf { kotlin.math.abs(it) }
                        val rms = kotlin.math.sqrt(window.map { it * it }.average().coerceAtLeast(0.0)).toFloat()
                        Log.i(
                            TAG,
                            "window done (QNN): sec=${window.size / 16000.0} peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} chars=${windowText.length}",
                        )
                    } else {
                        val cb = object : TranscriptionCallback {
                            override fun onProgress(percent: Int) {
                                val frac = if (totalMs > 0) {
                                    ((windowStartMs + windowMs * percent / 100.0) / totalMs).coerceIn(0.0, 0.999)
                                } else {
                                    0.999
                                }
                                bus.update { it.copy(progress = frac.toFloat()) }
                            }

                            override fun onSegment(startMs: Long, endMs: Long, segText: String): Boolean {
                                val trimmed = segText.trim()
                                if (trimmed.isNotEmpty()) {
                                    segments.add(TranscriptSegment(startMs + windowStartMs, endMs + windowStartMs, trimmed))
                                    text.append(trimmed).append(' ')
                                    bus.update { it.copy(partialText = text.toString().trim()) }
                                }
                                return true
                            }

                            override fun shouldContinue(): Boolean = !TranscriptionBus.cancelRequested
                        }

                        val ok = WhisperEngine.transcribe(ctx, window, s.language, s.translateToEnglish, cb)
                        val peak = window.maxOf { kotlin.math.abs(it) }
                        val rms = kotlin.math.sqrt(window.map { it * it }.average().coerceAtLeast(0.0)).toFloat()
                        Log.i(
                            TAG,
                            "window done: sec=${window.size / 16000.0} peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} segments=${segments.size} ok=$ok",
                        )
                        if (!ok) {
                            if (TranscriptionBus.cancelRequested) break
                            throw IOException("Whisper failed to transcribe this audio")
                        }
                    }

                    windowStartMs += windowMs
                    if (totalMs > 0) {
                        bus.update {
                            it.copy(progress = (windowStartMs.toDouble() / totalMs).coerceIn(0.0, 0.999).toFloat())
                        }
                    }
                }
            } finally {
                decoder.close()
                if (ctx != 0L) WhisperEngine.destroyContext(ctx)
            }

            val processingMs = SystemClock.elapsedRealtime() - startedAt
            if (TranscriptionBus.cancelRequested) {
                bus.update { it.copy(phase = JobPhase.CANCELLED) }
                return
            }
            Log.i(TAG, "done: segments=${segments.size} chars=${text.length} audioMs=$audioMs")

            val result = TranscriptResult(
                text = text.toString().trim(),
                segments = segments.toList(),
                fileName = displayName,
                modelId = modelId,
                modelLabel = modelLabel,
                backend = backend?.name ?: "QNN",
                language = s.language,
                audioDurationMs = maxOf(audioMs, segments.lastOrNull()?.endMs ?: 0L),
                processingMs = processingMs,
            )

            historyDao.insert(
                HistoryEntry(
                    fileName = result.fileName,
                    text = result.text,
                    language = result.language,
                    modelId = result.modelId,
                    modelLabel = result.modelLabel,
                    backend = result.backend,
                    audioDurationMs = result.audioDurationMs,
                    processingMs = result.processingMs,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            bus.update {
                it.copy(phase = JobPhase.DONE, result = result, progress = 1f, partialText = result.text)
            }
        } catch (ce: CancellationException) {
            bus.update { it.copy(phase = JobPhase.CANCELLED) }
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed", t)
            bus.update {
                it.copy(phase = JobPhase.ERROR, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun detectTier(): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return DeviceTier.detect(info.totalMem)
    }

    private fun pickBackend(pref: String, model: ModelInfo): BackendDevice {
        val gpus = WhisperEngine.gpuBackends()
        val npu = gpus.firstOrNull { it.name.startsWith("HTP") || it.name.contains("Hexagon", true) }
        val gpu = gpus.firstOrNull { !it.name.startsWith("HTP") && !it.name.contains("Hexagon", true) }
        // The Hexagon path supports q8_0/f32 quants; the default recommendations are q8_0.
        val npuUsable = npu != null && model.id.endsWith("-q8_0")
        return when (pref) {
            "npu" -> npu ?: gpu ?: cpuDevice()
            "qnn" -> npu ?: gpu ?: cpuDevice()
            "gpu" -> (gpu ?: npu ?: cpuDevice()).also {
                // GPU is an explicit opt-in: OpenCL aborts on some OEM drivers
                WhisperEngine.setOpenclEnabled(gpu != null && !it.name.startsWith("HTP"))
            }
            "cpu" -> cpuDevice()
            else -> if (npuUsable) npu!! else cpuDevice() // auto: NPU → CPU (OpenCL is opt-in)
        }
    }

    private fun cpuDevice(): BackendDevice =
        WhisperEngine.backends().firstOrNull { it.kind == "cpu" }
            ?: BackendDevice("CPU", "CPU", "cpu")

    private companion object {
        const val TAG = "Transcription"
    }
}
