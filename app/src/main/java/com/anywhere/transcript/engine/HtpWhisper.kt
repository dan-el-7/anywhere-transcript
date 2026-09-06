package com.anywhere.transcript.engine

/**
 * JNI binding for Whisper inference on the Hexagon NPU via the ONNX Runtime
 * C API + QNN Execution Provider (vendored from theedevguy/whisper-htp-android,
 * MIT). Native code is used because this model family's tensor types (uint16
 * affine-quantized activations, fp16) can't be expressed through the ORT Java API.
 */
object HtpWhisper {
    init {
        System.loadLibrary("whisper_htp")
    }

    const val VARIANT_SMALL_W8A16 = 0
    const val VARIANT_TURBO_FP16 = 1

    /** Streaming + cancellation hooks for the decode loop. */
    interface Listener {
        /** Partial generated ids so far. Return false to stop the decode. */
        fun onPartial(ids: IntArray): Boolean

        /** Polled every decode step; return false to stop. */
        fun shouldContinue(): Boolean
    }

    /** Creates the encoder + decoder QNN sessions. Returns 0 on failure (logcat tag HtpWhisper). */
    external fun nativeInit(
        nativeLibDir: String,
        encoderPath: String,
        decoderPath: String,
        variant: Int,
        listener: Listener?,
    ): Long

    /**
     * Runs one 30-second window: encoder on [mel], then a greedy fixed-shape
     * KV-cache decode seeded with [promptIds]. Returns generated token ids
     * (prompt and end-of-text excluded), or null on failure.
     * [metrics]: [0] mean sampled-token probability, [1] no-speech probability.
     */
    external fun nativeTranscribe(
        handle: Long,
        mel: ShortArray,
        promptIds: IntArray,
        metrics: FloatArray?,
    ): IntArray?

    external fun nativeFree(handle: Long)

    /** Loads the nMel x 201 float32 mel filterbank. */
    external fun nativeMelInit(filtersPath: String, nMel: Int): Boolean

    /**
     * Whisper log-mel spectrogram of up to 30s of 16kHz mono PCM in [-1,1].
     * Output carries raw fp16 bits (turbo contract) as shorts.
     */
    external fun nativeMel(pcm: FloatArray, fp16Out: Boolean): ShortArray?
}
