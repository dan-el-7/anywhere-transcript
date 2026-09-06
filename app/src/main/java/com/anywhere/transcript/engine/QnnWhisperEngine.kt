package com.anywhere.transcript.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Whisper Large-V3-Turbo (fp16) on the Hexagon NPU via QNN context binaries +
 * ONNX Runtime QNN EP. Model files (from Qualcomm AI Hub's precompiled package,
 * per-SoC build) live in the app's external files dir under qnn/:
 *   encoder.onnx, encoder_qairt_context.bin, decoder.onnx, decoder_qairt_context.bin
 * Mel filterbank + vocab ship as bundled assets.
 */
object QnnWhisperEngine {

    private const val TAG = "QnnWhisper"

    const val VARIANT_TURBO_FP16 = 1
    private const val N_MEL = 128
    private const val WINDOW_SEC = 30
    private const val SAMPLES_PER_WINDOW = WINDOW_SEC * 16000

    private var handle = 0L
    private var vocab: WhisperVocab? = null
    private var langTokenCache: MutableMap<String, Int> = mutableMapOf()
    private var melReady = false

    /** Streaming sink for the current window; set by the coordinator before each window. */
    @Volatile
    var partialSink: ((String) -> Unit)? = null

    /** Cancellation check polled by the native decode loop. */
    @Volatile
    var cancelCheck: () -> Boolean = { false }

    private var emitted = 0

    private val listener = object : HtpWhisper.Listener {
        override fun onPartial(ids: IntArray): Boolean {
            if (cancelCheck()) return false
            if (ids.size > emitted) {
                val v = vocab ?: return true
                val full = v.decode(ids).trim()
                val partial = full.substring(minOf(emitted, full.length))
                if (partial.isNotEmpty()) partialSink?.invoke(partial)
                emitted = ids.size
            }
            return true
        }

        override fun shouldContinue(): Boolean = !cancelCheck()
    }

    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), "qnn")

    fun modelsReady(context: Context): Boolean {
        val d = modelDir(context)
        return listOf("encoder.onnx", "encoder_qairt_context.bin", "decoder.onnx", "decoder_qairt_context.bin")
            .all { File(d, it).let { f -> f.exists() && f.length() > 0 } }
    }

    fun deleteExtracted(context: Context) {
        modelDir(context).deleteRecursively()
    }

    fun isInitialized(): Boolean = handle != 0L

    /** Prepares assets + opens the QNN sessions. Returns false on failure (see logcat). */
    fun initIfNeeded(context: Context): Boolean {
        if (handle != 0L) return true
        return try {
            val d = modelDir(context)
            // mel filters + vocab live as bundled assets; the native side needs file paths
            val assetsDir = File(context.filesDir, "qnn-assets").apply { mkdirs() }
            val filters = File(assetsDir, "mel_filters_128.bin")
            val vocabFile = File(assetsDir, "whisper_vocab_v3.json")
            if (!filters.exists()) context.assets.open("mel_filters_128.bin").use { i -> filters.outputStream().use { i.copyTo(it) } }
            if (!vocabFile.exists()) context.assets.open("whisper_vocab_v3.json").use { i -> vocabFile.outputStream().use { i.copyTo(it) } }

            if (!HtpWhisper.nativeMelInit(filters.absolutePath, N_MEL)) {
                Log.e(TAG, "mel init failed")
                return false
            }
            vocab = WhisperVocab(vocabFile)

            handle = HtpWhisper.nativeInit(
                context.applicationInfo.nativeLibraryDir,
                File(d, "encoder.onnx").absolutePath,
                File(d, "decoder.onnx").absolutePath,
                HtpWhisper.VARIANT_TURBO_FP16,
                listener,
            )
            val ok = handle != 0L
            Log.i(TAG, "init: handle=$handle")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            false
        }
    }

    fun release() {
        if (handle != 0L) {
            HtpWhisper.nativeFree(handle)
            handle = 0L
        }
    }

    private fun langToken(languageIso: String): Int {
        val v = vocab ?: return -1
        val key = languageIso.ifBlank { "en" }.lowercase()
        langTokenCache[key]?.let { return it }
        val id = v.idOf("<|$key|>")
        langTokenCache[key] = id
        return id
    }

    /**
     * Transcribes one ≤30s window of 16kHz mono PCM. Language "auto" maps to
     * English (turbo's decode loop uses a fixed prompt; auto-detect lands in v2.1).
     * [onPartialText] receives streaming text as tokens are decoded.
     */
    fun transcribeWindow(
        context: Context,
        pcm: FloatArray,
        languageIso: String,
        onPartialText: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        if (!initIfNeeded(context)) return ""
        val v = vocab ?: return ""

        emitted = 0
        partialSink = onPartialText
        cancelCheck = isCancelled

        val clipped = if (pcm.size > SAMPLES_PER_WINDOW) pcm.copyOf(SAMPLES_PER_WINDOW) else pcm
        val padded = if (clipped.size < SAMPLES_PER_WINDOW) {
            clipped.copyOf(SAMPLES_PER_WINDOW) // zero-padded to the fixed 30s shape
        } else clipped

        val mel = HtpWhisper.nativeMel(padded, fp16Out = true)
            ?: run { Log.e(TAG, "mel failed"); return "" }

        val langId = langToken(languageIso).let { if (it >= 0) it else v.idOf("<|en|>") }
        val sot = v.idOf("<|startoftranscript|>")
        val transcribe = v.idOf("<|transcribe|>")
        val notimestamps = v.idOf("<|notimestamps|>")
        if (langId < 0 || sot < 0 || transcribe < 0 || notimestamps < 0) {
            Log.e(TAG, "special tokens not found in vocab")
            return ""
        }
        val prompt = intArrayOf(sot, langId, transcribe, notimestamps)

        val metrics = FloatArray(4)
        val tokens = HtpWhisper.nativeTranscribe(handle, mel, prompt, metrics)
            ?: run { Log.e(TAG, "transcribe failed (metrics no_speech=${metrics.getOrElse(1) { 0f }})"); return "" }

        val text = v.decode(tokens).trim()
        Log.d(TAG, "window: ${tokens.size} tokens, no_speech=${"%.3f".format(metrics.getOrElse(1) { 0f })}")
        return text
    }
}
