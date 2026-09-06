package com.anywhere.transcript.transcription

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.anywhere.transcript.audio.AudioDecoder
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.ModelRepository
import com.anywhere.transcript.data.SettingsRepository
import com.anywhere.transcript.data.db.HistoryEntry
import com.anywhere.transcript.engine.BackendDevice
import com.anywhere.transcript.engine.TranscriptionCallback
import com.anywhere.transcript.engine.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs the full pipeline for one file: pick model/backend → open decoder →
 * windowed whisper transcription (streaming partials, cancellable) → history.
 */
class TranscriptionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val modelRepo: ModelRepository,
    private val historyDao: com.anywhere.transcript.data.db.HistoryDao,
) {
    private var job: Job? = null

    fun start(uri: Uri, displayName: String) {
        if (job?.isActive == true) return
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
            val model = modelRepo.selectedOrDefault(s, tier)

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

            val backend = pickBackend(s.backendPref, model)
            android.util.Log.i(
                TAG,
                "job start: model=${model.id} backend=${backend.name} pref=${s.backendPref} tier=$tier",
            )
            bus.update {
                it.copy(
                    phase = JobPhase.PREPARING,
                    fileName = displayName,
                    modelId = model.id,
                    modelLabel = model.label,
                    backend = backend.name,
                )
            }

            val ctx = WhisperEngine.createContext(
                modelRepo.modelFile(model.id).absolutePath,
                backend.name,
                WhisperEngine.defaultThreads(),
            )
            if (ctx == 0L) {
                bus.update {
                    it.copy(phase = JobPhase.ERROR, error = "Could not load model “${model.label}”.")
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

                val windowSec = when (tier) {
                    DeviceTier.LOW -> 60
                    DeviceTier.MID -> 120
                    DeviceTier.FLAGSHIP -> 300
                }
                val totalMs = opened.durationMs
                var windowStartMs = 0L

                while (true) {
                    if (TranscriptionBus.cancelRequested) break
                    val window = decoder.nextWindow(windowSec) { TranscriptionBus.cancelRequested } ?: break
                    val windowMs = window.size / 16L
                    bus.update { it.copy(phase = JobPhase.TRANSCRIBING) }

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
                    android.util.Log.i(
                        TAG,
                        "window done: sec=${window.size / 16000.0} peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} segments=${segments.size} ok=$ok",
                    )
                    if (!ok) {
                        if (TranscriptionBus.cancelRequested) break
                        throw IOException("Whisper failed to transcribe this audio")
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
                WhisperEngine.destroyContext(ctx)
            }

            val processingMs = SystemClock.elapsedRealtime() - startedAt
            if (TranscriptionBus.cancelRequested) {
                bus.update { it.copy(phase = JobPhase.CANCELLED) }
                return
            }
            android.util.Log.i(TAG, "done: segments=${segments.size} chars=${text.length} audioMs=$audioMs")

            val result = TranscriptResult(
                text = text.toString().trim(),
                segments = segments.toList(),
                fileName = displayName,
                modelId = model.id,
                modelLabel = model.label,
                backend = backend.name,
                language = s.language,
                audioDurationMs = maxOf(audioMs, windowStartMsCompat(segments)),
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

    private fun pickBackend(pref: String, model: com.anywhere.transcript.data.ModelInfo): BackendDevice {
        val gpus = WhisperEngine.gpuBackends()
        val npu = gpus.firstOrNull { it.name.startsWith("HTP") || it.name.contains("Hexagon", true) }
        val gpu = gpus.firstOrNull { !it.name.startsWith("HTP") && !it.name.contains("Hexagon", true) }
            ?: gpus.firstOrNull()
        // The Hexagon path supports q8_0/f32 quants; the default recommendations are q8_0.
        val npuUsable = npu != null && model.id.endsWith("-q8_0")
        return when (pref) {
            "npu" -> npu ?: gpu ?: cpuDevice()
            "cpu" -> cpuDevice()
            "gpu" -> gpu ?: cpuDevice()
            else -> if (npuUsable) npu!! else gpu ?: cpuDevice() // auto: NPU → GPU → CPU
        }
    }

    private fun cpuDevice(): BackendDevice =
        WhisperEngine.backends().firstOrNull { it.kind == "cpu" }
            ?: BackendDevice("CPU", "CPU", "cpu")

    private fun windowStartMsCompat(segments: List<TranscriptSegment>): Long =
        segments.lastOrNull()?.endMs ?: 0L

    private companion object {
        const val TAG = "Transcription"
    }
}
