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

            // Engine is chosen by the model, not just the preference: QNN
            // context-binary packages can ONLY run on the QNN engine, so a
            // selected qnn-* model routes there under every backend preference.
            // A "qnn" PREFERENCE also routes there when this chip has the
            // package — the Transcribe screen already labels that state
            // "QNN · Turbo fp16 (Hexagon NPU)", so the coordinator must
            // actually do it (a ggml model picked underneath is ignored).
            val model: ModelInfo? = modelRepo.selectedOrDefault(s, tier)
            val hexArch = com.anywhere.transcript.data.Hexagon.deviceArch()
            val qnnReadyHere = hexArch != null && QnnWhisperEngine.modelsReady(context, hexArch)
            val useQnn = backendOverride == "qnn" ||
                model?.id?.startsWith("qnn-turbo") == true ||
                (s.backendPref == "qnn" && qnnReadyHere)
            var modelLabel: String
            var modelId: String
            var qnnArch = ""
            // Whisper-engine model path; stays null on the QNN path.
            var modelPath: String? = null
            if (useQnn) {
                qnnArch = model?.id?.removePrefix("qnn-turbo-")?.takeIf { it != model?.id }
                    ?: (hexArch ?: "v79")
                if (!QnnWhisperEngine.modelsReady(context, qnnArch)) {
                    bus.update {
                        it.copy(
                            phase = JobPhase.ERROR,
                            fileName = displayName,
                            error = "QNN model files aren't in the app's files/qnn/$qnnArch folder yet.",
                            modelMissing = true,
                        )
                    }
                    return
                }
                modelId = "qnn-turbo-$qnnArch"
                modelLabel = com.anywhere.transcript.data.ModelCatalog.byId[modelId]?.label
                    ?: "Large-V3-Turbo QNN ($qnnArch)"
            } else {
                // No usable ggml model: fail loudly (with a path to the Models
                // tab) instead of silently doing nothing — the old `?: return`
                // left the UI stuck in IDLE with a foreground service hanging.
                val m = model ?: run {
                    bus.update {
                        it.copy(
                            phase = JobPhase.ERROR,
                            fileName = displayName,
                            error = "No usable model. Download one from the Models tab first" +
                                " (or the QNN Turbo package for this chip).",
                            modelMissing = true,
                        )
                    }
                    return
                }
                if (!modelRepo.isDownloaded(m.id)) {
                    bus.update {
                        it.copy(
                            phase = JobPhase.ERROR,
                            fileName = displayName,
                            error = "Model “${m.label}” isn't downloaded yet. Get it from the Models tab.",
                            modelMissing = true,
                        )
                    }
                    return
                }
                modelId = m.id
                modelLabel = m.label
                modelPath = modelRepo.modelFile(m.id).absolutePath
            }

            var backend = if (useQnn) null else pickBackend(s.backendPref)
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

            var ctx = 0L
            if (!useQnn) {
                ctx = WhisperEngine.createContext(
                    modelPath!!,
                    backend!!.name,
                    WhisperEngine.defaultThreads(),
                )
                if (ctx == 0L && backend.name != cpuDevice().name) {
                    // Backend init can fail at runtime (OEM-gated DSP sessions,
                    // flaky GPU drivers): retry on CPU instead of killing the
                    // job — the README promises automatic CPU fallback.
                    Log.w(TAG, "context init failed on ${backend.name}, retrying on CPU")
                    // GPU init may have left the shim half-loaded; make sure
                    // the CPU retry can't trip over it
                    WhisperEngine.setOpenclEnabled(false)
                    backend = cpuDevice()
                    ctx = WhisperEngine.createContext(
                        modelPath!!,
                        backend.name,
                        WhisperEngine.defaultThreads(),
                    )
                    if (ctx != 0L) bus.update { it.copy(backend = backend.name) }
                }
                if (ctx == 0L) {
                    bus.update {
                        it.copy(phase = JobPhase.ERROR, error = "Could not load model “$modelLabel”.")
                    }
                    return
                }
            }
            if (useQnn && !QnnWhisperEngine.initIfNeeded(context, qnnArch)) {
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
                        // live partials stream into the UI; the authoritative window
                        // text is appended once the window's decode finishes
                        var streamed = ""
                        val windowText = QnnWhisperEngine.transcribeWindow(
                            context,
                            window,
                            s.language.ifBlank { "auto" },
                            translate = s.translateToEnglish,
                            onPartialText = { delta ->
                                streamed += delta
                                bus.update { it.copy(partialText = streamed.trim()) }
                            },
                            isCancelled = { TranscriptionBus.cancelRequested },
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

    private fun pickBackend(pref: String): BackendDevice {
        // The OpenCL shim is opt-in (uncatchable aborts on some OEM drivers).
        // A GPUOpenCL device sits in ggml's registry even while the shim is
        // disabled — using it then aborts natively — so GPU only counts as
        // "available" once the user opted in (settings side-channel flag).
        val gpuAllowed = context.getSharedPreferences("engine_flags", Context.MODE_PRIVATE)
            .getBoolean("opencl_enabled", false)
        val gpu = if (gpuAllowed) {
            WhisperEngine.gpuBackends()
                .firstOrNull { !it.name.startsWith("HTP") && !it.name.contains("Hexagon", true) }
        } else null
        // No direct-NPU dispatch: the HTP runs precompiled graphs only and app
        // DSP sessions are OEM-gated. The NPU is reached exclusively via the
        // QNN engine (precompiled context binaries). Legacy "npu"/"qnn" prefs
        // land here only for ggml models on chips without the QNN package →
        // same as auto.
        return when (pref) {
            "gpu" -> (gpu ?: cpuDevice()).also {
                WhisperEngine.setOpenclEnabled(gpu != null)
            }
            "cpu" -> cpuDevice()
            else -> gpu ?: cpuDevice()
        }
    }

    private fun cpuDevice(): BackendDevice =
        WhisperEngine.backends().firstOrNull { it.kind == "cpu" }
            ?: BackendDevice("CPU", "CPU", "cpu")

    private companion object {
        const val TAG = "Transcription"
    }
}
