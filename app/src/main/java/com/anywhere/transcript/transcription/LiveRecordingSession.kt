package com.anywhere.transcript.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.anywhere.transcript.audio.RateConverter
import com.anywhere.transcript.audio.WavWriter
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.Hexagon
import com.anywhere.transcript.data.ModelInfo
import com.anywhere.transcript.data.ModelRepository
import com.anywhere.transcript.data.SettingsRepository
import com.anywhere.transcript.engine.QnnWhisperEngine
import com.anywhere.transcript.engine.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Live capture state, exposed to the Record screen. */
data class RecordUiState(
    val recording: Boolean = false,
    /** Model/engine load in progress (record start is blocked until done). */
    val preloading: Boolean = false,
    val elapsedMs: Long = 0L,
    /** Text decoded so far (live chunks + tail). */
    val liveText: String = "",
    val error: String? = null,
    val modelMissing: Boolean = false,
)

/**
 * Microphone capture + live transcription in one session.
 *
 * Audio: AudioRecord at the hardware rate (48 kHz mono), linear-resampled to
 * 16 kHz. Every CHUNK_SEC of speech the accumulated PCM is transcribed
 * *while recording continues* — on QNN a chunk costs ~1 s of NPU time for
 * 5 s of speech, so live text tracks the speaker closely. The take is also
 * written to a 16 kHz WAV as it streams, so the end-of-recording pass can
 * re-transcribe it with full context (window boundary fixups, History entry)
 * exactly like a picked file.
 *
 * Live chunks mirror the coordinator's routing: qnn-turbo-* selected (or
 * backendPref "qnn" with the package present) → QNN; else whisper.cpp CPU.
 * If live decoding fails mid-take, capture continues and the final pass
 * still runs (end-of-recording fallback).
 */
class LiveRecordingSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val modelRepo: ModelRepository,
) {
    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val stopping = AtomicBoolean(false)

    /** Fired when a take finishes; the VM turns it into a Transcribe job. */
    var onTakeFinished: ((file: File) -> Unit)? = null

    fun start() {
        if (job?.isActive == true) return
        stopping.set(false)
        job = scope.launch(Dispatchers.IO) {
            // Preload the engine BEFORE capture begins: QNN session setup
            // (or a ggml context create) costs 10–20 s on first use, which
            // otherwise lands on the first live chunk while the user is
            // already speaking — text lags far behind the audio.
            _state.value = _state.value.copy(preloading = true)
            val engineReady = preloadEngine()
            _state.value = _state.value.copy(preloading = false)
            if (engineReady) recordLoop()
        }
    }

    /**
     * Loads the engine ahead of capture. Returns false (with an error state)
     * when no usable model exists — recording then never starts.
     */
    private suspend fun preloadEngine(): Boolean {
        return try {
            val s = settingsRepo.current()
            val model: ModelInfo? = modelRepo.selectedOrDefault(s, detectTier())
            val hexArch = Hexagon.deviceArch()
            val qnnReady = hexArch != null && QnnWhisperEngine.modelsReady(context, hexArch)
            val useQnn = qnnReady && (model?.id?.startsWith("qnn-turbo") == true || s.backendPref == "qnn")
            if (useQnn) {
                if (!QnnWhisperEngine.initIfNeeded(context, hexArch!!)) {
                    _state.update { it.copy(error = "QNN init failed — see logcat (tag QnnWhisper).") }
                    return false
                }
            } else {
                val m = model ?: run {
                    _state.update {
                        it.copy(error = "No usable model. Download one from the Models tab first.", modelMissing = true)
                    }
                    return false
                }
                if (!modelRepo.isDownloaded(m.id)) {
                    _state.update {
                        it.copy(error = "Model “${m.label}” isn't downloaded yet. Get it from the Models tab.", modelMissing = true)
                    }
                    return false
                }
                warmCtx = WhisperEngine.createContext(
                    modelRepo.modelFile(m.id).absolutePath,
                    "CPU",
                    WhisperEngine.defaultThreads(),
                )
                if (warmCtx == 0L) {
                    _state.update { it.copy(error = "Could not load model “${m.label}”.") }
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "preload failed", t)
            _state.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
            false
        }
    }

    /** whisper.cpp context created at preload; reused by the capture loop. */
    @Volatile
    private var warmCtx: Long = 0L

    fun stop() {
        stopping.set(true)
    }

    fun reset() {
        _state.value = RecordUiState()
    }

    // ---------------------------------------------------------------- loop

    private suspend fun recordLoop() {
        val startedAt = SystemClock.elapsedRealtime()
        var recorder: AudioRecord? = null
        val wavFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.wav")
        var wav: WavWriter? = null
        var transcribingJob: Job? = null
        var chunkChannel: Channel<ShortArray>? = null

        try {
            val s = settingsRepo.current()

            // ---- engine + model routing (mirrors TranscriptionCoordinator;
            // engine was already initialized by preloadEngine)
            val model: ModelInfo? = modelRepo.selectedOrDefault(s, detectTier())
            val hexArch = Hexagon.deviceArch()
            val qnnReady = hexArch != null && QnnWhisperEngine.modelsReady(context, hexArch)
            val useQnn = qnnReady && (model?.id?.startsWith("qnn-turbo") == true || s.backendPref == "qnn")
            val ctxHandle = warmCtx // 0 on the QNN path

            // ---- AudioRecord setup
            val minBuf = AudioRecord.getMinBufferSize(
                SRC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = maxOf(minBuf, SRC_RATE * 2 * 2) // ≥ 2 s of audio
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SRC_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (t: Throwable) {
                null
            }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                _state.update { it.copy(error = "Microphone unavailable on this device.") }
                return
            }

            _state.value = RecordUiState(recording = true)

            wav = WavWriter(wavFile)
            val resampler = RateConverter(SRC_RATE, 16000)
            val chunkSamples = CHUNK_SEC * 16000
            val window = ShortArray(chunkSamples)
            var windowFill = 0
            val srcBuf = ShortArray(2048)

            val channel = Channel<ShortArray>(Channel.UNLIMITED)
            chunkChannel = channel
            transcribingJob = scope.launch(Dispatchers.Default) {
                for (chunk in channel) {
                    if (stopping.get()) break
                    transcribeChunk(chunk, ctxHandle, useQnn, s.language.ifBlank { "auto" }, s.translateToEnglish)
                }
            }

            recorder.startRecording()
            while (!stopping.get()) {
                val n = recorder.read(srcBuf, 0, srcBuf.size)
                if (n <= 0) continue

                // resample to 16 kHz first: both the live window and the WAV
                // consume the SAME 16 kHz stream (WavWriter declares 16 kHz)
                val floats = FloatArray(n) { j -> srcBuf[j] / 32768f }
                val out = resampler.process(floats)
                if (out.isEmpty()) continue
                val pcm16 = ShortArray(out.size) {
                    (out[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                wav.write(pcm16)

                var fo = 0
                while (fo < pcm16.size) {
                    val space = window.size - windowFill
                    val take = minOf(space, pcm16.size - fo)
                    System.arraycopy(pcm16, fo, window, windowFill, take)
                    windowFill += take
                    fo += take
                    if (windowFill == window.size) {
                        channel.trySend(window.copyOf())
                        windowFill = 0
                    }
                }
                _state.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt) }
            }

            // Immediately stop hardware recorder once stop is requested
            runCatching { recorder.stop() }
            channel.close()

            // Flush resampler tail
            val flushed = resampler.flush()
            if (flushed.isNotEmpty()) {
                val tailPcm = ShortArray(flushed.size) {
                    (flushed[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                wav.write(tailPcm)
                var fo = 0
                while (fo < tailPcm.size) {
                    val space = window.size - windowFill
                    val take = minOf(space, tailPcm.size - fo)
                    System.arraycopy(tailPcm, fo, window, windowFill, take)
                    windowFill += take
                    fo += take
                }
            }

            // Stop background worker before releasing native resources
            transcribingJob?.cancelAndJoin()
            transcribingJob = null

            // ---- tail (partial window) as the final live chunk (if not cancelled)
            if (windowFill > 0 && !stopping.get()) {
                transcribeChunk(window.copyOf(windowFill), ctxHandle, useQnn, s.language.ifBlank { "auto" }, s.translateToEnglish)
            }
            if (warmCtx != 0L) {
                WhisperEngine.destroyContext(warmCtx)
                warmCtx = 0L
            }

            // ---- end-of-recording: full pass over the 16 kHz WAV
            val totalMs = SystemClock.elapsedRealtime() - startedAt
            wav.close()
            wav = null
            _state.update { it.copy(recording = false, elapsedMs = totalMs) }

            if (wavFile.length() > MIN_TAKE_BYTES) {
                lastFiles.addFirst(wavFile)
                if (lastFiles.size > 3) lastFiles.removeLastOrNull()?.delete()
                onTakeFinished?.invoke(wavFile)
            } else {
                wavFile.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "live recording failed", t)
            _state.update { it.copy(recording = false, error = t.message ?: t.javaClass.simpleName) }
        } finally {
            runCatching { wav?.close() }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            transcribingJob?.cancel()
            // any path that escapes the loop (exception before stop) must
            // still release the preloaded whisper.cpp context
            if (warmCtx != 0L) {
                runCatching { WhisperEngine.destroyContext(warmCtx) }
                warmCtx = 0L
            }
        }
    }

    /** Last few takes, newest first (debug aid; auto-pruned to 3). */
    private val lastFiles = ArrayDeque<File>()

    /** One live chunk through either engine; failures never kill capture. */
    private fun transcribeChunk(chunk: ShortArray, ctxHandle: Long, useQnn: Boolean, language: String, translate: Boolean) {
        if (chunk.isEmpty()) return
        runCatching {
            val baseText = _state.value.liveText.trim()
            if (useQnn) {
                val pcm = FloatArray(chunk.size) { j -> chunk[j] / 32768f }
                val text = QnnWhisperEngine.transcribeWindow(
                    context,
                    pcm,
                    language,
                    translate = translate,
                    onPartialText = { chunkText ->
                        val currentText = if (baseText.isEmpty()) chunkText.trim() else "$baseText ${chunkText.trim()}"
                        _state.update { st -> st.copy(liveText = currentText) }
                    },
                    isCancelled = { stopping.get() },
                )
                if (text.isNotBlank()) {
                    val finalText = if (baseText.isEmpty()) text.trim() else "$baseText ${text.trim()}"
                    _state.update { st -> st.copy(liveText = finalText) }
                }
            } else if (ctxHandle != 0L) {
                val pcm = FloatArray(chunk.size) { j -> chunk[j] / 32768f }
                val sb = StringBuilder()
                val cb = object : com.anywhere.transcript.engine.TranscriptionCallback {
                    override fun onProgress(percent: Int) {}
                    override fun onSegment(startMs: Long, endMs: Long, text: String): Boolean {
                        if (text.isNotBlank()) sb.append(text.trim()).append(' ')
                        return true
                    }
                    override fun shouldContinue(): Boolean = !stopping.get()
                }
                WhisperEngine.transcribe(ctxHandle, pcm, language, translate, cb)
                val chunkText = sb.toString().trim()
                if (chunkText.isNotBlank()) {
                    val finalText = if (baseText.isEmpty()) chunkText else "$baseText $chunkText"
                    _state.update { st -> st.copy(liveText = finalText) }
                }
            }
        }.onFailure { Log.w(TAG, "live chunk failed: ${it.message}") }
    }

    private fun detectTier(): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return DeviceTier.detect(info.totalMem)
    }

    private companion object {
        const val TAG = "LiveRecord"
        const val SRC_RATE = 48000
        const val CHUNK_SEC = 5
        const val MIN_TAKE_BYTES = 44 + 16000 * 2 * 2L // header + ≥2 s of audio
    }
}
