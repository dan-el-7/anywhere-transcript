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

    // Special-token ids for the AI Hub context-binary exports. The vocab file
    // stores specials as empty entries, so these are fixed constants (verified
    // against theedevguy's working demo for these exact binaries):
    // EOT=50257 (native), SOT=50258, languages from 50259, and the v3 (turbo)
    // transcribe/notimestamps ids DIFFER from the v2 (small) ones.
    private const val SOT = 50258
    private const val TRANSCRIBE = 50360
    private const val TRANSLATE = 50359
    private const val NOTIMESTAMPS = 50364

    // Sentinel: prompt[1] = LANG_AUTO makes the native decoder argmax the
    // language-token logits at the SOT step and substitute the detected id.
    private const val LANG_AUTO = -1

    // Whisper language-token order; id = LANG_FIRST + index (matches the
    // native side's LANG_FIRST/LANG_EN/LANG_PT).
    private const val LANG_FIRST = 50259
    private val LANG_ORDER = listOf(
        "en", "zh", "de", "es", "ru", "ko", "ja", "fr", "pt", "tr", "pl", "ca",
        "nl", "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms",
        "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la",
        "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn",
        "et", "mk", "br", "ga", "sq", "is", "hy", "ne", "mn", "bs", "kk",
    )

    private var handle = 0L
    private var vocab: WhisperVocab? = null
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

    fun modelDir(context: Context, arch: String): File =
        File(context.getExternalFilesDir(null), "qnn/$arch")

    /** One directory per Hexagon arch: packages are arch-locked and must not mix. */
    fun modelsReady(context: Context, arch: String): Boolean {
        migrateLegacy(context, arch)
        val d = modelDir(context, arch)
        return listOf("encoder.onnx", "encoder_qairt_context.bin", "decoder.onnx", "decoder_qairt_context.bin")
            .all { File(d, it).let { f -> f.exists() && f.length() > 0 } }
    }

    fun deleteExtracted(context: Context, arch: String) {
        modelDir(context, arch).deleteRecursively()
    }

    fun isInitialized(): Boolean = handle != 0L

    /**
     * Older builds extracted every arch into qnn/ directly; move this arch's
     * files into the per-arch folder. The app performs the move itself so the
     * directory has app-owned permissions (dirs made by adb push as shell are
     * not readable by the app). Guarded to this device's arch: readiness checks
     * for other archs must not adopt the files.
     */
    private fun migrateLegacy(context: Context, arch: String) {
        val deviceArch = com.anywhere.transcript.data.Hexagon.deviceArch() ?: "v79"
        if (arch != deviceArch) return
        val legacy = File(context.getExternalFilesDir(null), "qnn")
        val dst = modelDir(context, arch)
        var moved = false
        listOf("encoder.onnx", "encoder_qairt_context.bin", "decoder.onnx", "decoder_qairt_context.bin")
            .forEach { name ->
                val src = File(legacy, name)
                val target = File(dst, name)
                if (src.isFile && !target.exists()) {
                    if (dst.mkdirs() || dst.isDirectory) moved = src.renameTo(target) || moved
                }
            }
        if (moved) Log.i(TAG, "migrated legacy qnn/ layout -> qnn/$arch")
    }

    /** Prepares assets + opens the QNN sessions. Returns false on failure (see logcat). */
    fun initIfNeeded(context: Context, arch: String): Boolean {
        if (handle != 0L) return true
        return try {
            val d = modelDir(context, arch)
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
        val key = languageIso.ifBlank { "auto" }.lowercase()
        // "auto" → LANG_AUTO sentinel: native decodes the language-token argmax
        // at the SOT step and substitutes it, so output follows the spoken
        // language instead of always conditioning on <|en|> (= silent EN
        // translation of foreign speech).
        val idx = LANG_ORDER.indexOf(key).let { if (it >= 0) it else 0 }
        return if (key == "auto") LANG_AUTO else LANG_FIRST + idx
    }

    /**
     * Transcribes one ≤30s window of 16kHz mono PCM. Language "auto" triggers
     * native language detection (output follows the spoken language).
     * [translate] = true swaps the task token to TRANSLATE → English output.
     * [onPartialText] receives streaming text as tokens are decoded.
     */
    fun transcribeWindow(
        context: Context,
        pcm: FloatArray,
        languageIso: String,
        translate: Boolean = false,
        onPartialText: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        if (handle == 0L) {
            Log.e(TAG, "transcribeWindow before init")
            return ""
        }
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

        val langId = if (translate) LANG_FIRST else langToken(languageIso)
        // Empirically (see probe logs 09-07): this AI Hub export ignores the
        // task-token slot — every task id 50357..50361 yields identical output.
        // Output language is driven by the LANGUAGE token instead: pre-fix runs
        // with <|en|> conditioning translated foreign speech to English even on
        // the transcribe task. So translate=true forces <|en|> + <|translate|>
        // (kept for prompt-shape fidelity with whisper.cpp).
        val task = if (translate) TRANSLATE else TRANSCRIBE
        val prompt = intArrayOf(SOT, langId, task, NOTIMESTAMPS)
        Log.i(TAG, "qnn prompt=${prompt.contentToString()} translate=$translate")

        val metrics = FloatArray(4)
        val tokens = HtpWhisper.nativeTranscribe(handle, mel, prompt, metrics)
            ?: run { Log.e(TAG, "transcribe failed (metrics no_speech=${metrics.getOrElse(1) { 0f }})"); return "" }

        val text = v.decode(tokens).trim()
        Log.d(TAG, "window: ${tokens.size} tokens, no_speech=${"%.3f".format(metrics.getOrElse(1) { 0f })}")
        return text
    }
}
