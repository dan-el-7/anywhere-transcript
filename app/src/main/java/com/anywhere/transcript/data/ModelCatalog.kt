package com.anywhere.transcript.data

/**
 * Whisper ggml models hosted on Hugging Face (ggerganov/whisper.cpp).
 * Sizes are approximate (used for display and rough sanity checks only).
 * q8_0 is near-lossless and is also the quant family supported by the
 * experimental Hexagon NPU path.
 */
data class ModelInfo(
    val id: String,
    val file: String,
    val sizeBytes: Long,
    val tier: DeviceTier,
    val multilingual: Boolean,
    val params: String,
    val note: String? = null,
    /** Explicit download URL (custom models); null = default HF repo file. */
    val explicitUrl: String? = null,
) {
    val url: String get() = explicitUrl ?: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    val label: String = if (explicitUrl != null) {
        file.removeSuffix(".bin").removePrefix("ggml-").replaceFirstChar { it.uppercase() }
    } else buildString {
        val base = id.substringBefore("-").replaceFirstChar { it.uppercase() }
        append(base)
        if (id.contains("large-v3-turbo")) {
            clear()
            append("Large-v3 Turbo")
        } else if (id.contains("large-v3")) {
            clear()
            append("Large v3")
        }
        val quant = when {
            id.endsWith("-q8_0") || "-q8_0." in id -> "q8_0"
            id.endsWith("-q5_0") || "-q5_0." in id -> "q5_0"
            id.endsWith("-q5_1") || "-q5_1." in id -> "q5_1"
            else -> null
        }
        if (quant != null) append(" · ").append(quant)
        if (!multilingual) append(" · EN")
    }
}

object ModelCatalog {
    private const val MB = 1024L * 1024

    val all: List<ModelInfo> = listOf(
        // ---- Low-end (4 GB) ----
        ModelInfo("tiny-q8_0", "ggml-tiny-q8_0.bin", 42 * MB, DeviceTier.LOW, true, "39M"),
        ModelInfo("tiny.en-q8_0", "ggml-tiny.en-q8_0.bin", 42 * MB, DeviceTier.LOW, false, "39M"),
        ModelInfo("base-q8_0", "ggml-base-q8_0.bin", 78 * MB, DeviceTier.LOW, true, "74M"),
        ModelInfo("base.en-q8_0", "ggml-base.en-q8_0.bin", 78 * MB, DeviceTier.LOW, false, "74M"),
        ModelInfo("base-q5_1", "ggml-base-q5_1.bin", 57 * MB, DeviceTier.LOW, true, "74M"),
        // ---- Mid-range (8 GB) ----
        ModelInfo("small-q8_0", "ggml-small-q8_0.bin", 256 * MB, DeviceTier.MID, true, "244M"),
        ModelInfo("small-q5_1", "ggml-small-q5_1.bin", 181 * MB, DeviceTier.MID, true, "244M"),
        ModelInfo("small.en-q8_0", "ggml-small.en-q8_0.bin", 256 * MB, DeviceTier.MID, false, "244M"),
        ModelInfo("medium-q5_0", "ggml-medium-q5_0.bin", 514 * MB, DeviceTier.MID, true, "769M", "Slower"),
        // ---- Flagship (16 GB) ----
        ModelInfo("large-v3-turbo-q8_0", "ggml-large-v3-turbo-q8_0.bin", 834 * MB, DeviceTier.FLAGSHIP, true, "809M"),
        ModelInfo("large-v3-turbo-q5_0", "ggml-large-v3-turbo-q5_0.bin", 547 * MB, DeviceTier.FLAGSHIP, true, "809M"),
        ModelInfo("large-v3-q5_0", "ggml-large-v3-q5_0.bin", 574 * MB, DeviceTier.FLAGSHIP, true, "1.5B", "Max accuracy · slower"),
        ModelInfo("medium-q8_0", "ggml-medium-q8_0.bin", 786 * MB, DeviceTier.FLAGSHIP, true, "769M", "Slower"),
    )

    val byId: Map<String, ModelInfo> = all.associateBy { it.id }

    /** Default model per device tier. */
    val recommended: Map<DeviceTier, String> = mapOf(
        DeviceTier.LOW to "base-q8_0",
        DeviceTier.MID to "small-q8_0",
        DeviceTier.FLAGSHIP to "large-v3-turbo-q8_0",
    )

    fun recommendedFor(tier: DeviceTier): ModelInfo = byId.getValue(recommended.getValue(tier))

    fun forTier(tier: DeviceTier): List<ModelInfo> = all.filter { it.tier == tier }
}
