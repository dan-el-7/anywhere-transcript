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
    /** Hexagon arch this QNN context-binary package is compiled for (qnn packages only). */
    val qnnArch: String? = null,
    /** Human-readable SoC names this QNN package runs on, e.g. "Snapdragon 8 Elite". */
    val socLabel: String? = null,
    val labelOverride: String? = null,
) {
    val url: String get() = explicitUrl ?: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    val label: String = labelOverride ?: if (explicitUrl != null) {
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
        // ---- QNN NPU packages (Whisper-Large-V3-Turbo fp16, Qualcomm AI Hub) ----
        // Zips contain encoder/decoder ONNX + qairt context binaries; the app
        // extracts them to files/qnn for the QNN engine. Context binaries are
        // arch-locked: a v79 build only loads on a Hexagon v79 DSP, so the app
        // detects the chip and steers the user to the matching package.
        // Official listing: https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo
        qnnModel("v69", "qualcomm_snapdragon_8gen1", "Snapdragon 8 Gen 1 / 8+ Gen 1", 1624630419L),
        qnnModel("v73", "qualcomm_qcs8550_proxy", "Snapdragon 8 Gen 2 / 8s Gen 3", 2018899790L),
        qnnModel("v75", "qualcomm_snapdragon_8gen3", "Snapdragon 8 Gen 3", 2018865656L),
        qnnModel("v79", "qualcomm_snapdragon_8_elite_for_galaxy", "Snapdragon 8 Elite", 2016549535L),
        qnnModel("v81", "qualcomm_snapdragon_8_elite_gen5_for_galaxy", "Snapdragon 8 Elite Gen 5", 2016742045L),
    )

    /** Qualcomm AI Hub precompiled Whisper-Large-V3-Turbo (fp16) for a Hexagon arch. */
    private fun qnnModel(arch: String, socKey: String, socLabel: String, sizeBytes: Long): ModelInfo {
        val url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/" +
            "qai-hub-models/models/whisper_large_v3_turbo/releases/v0.61.0/" +
            "whisper_large_v3_turbo-precompiled_qnn_onnx-float-${socKey}.zip"
        return ModelInfo(
            id = "qnn-turbo-$arch",
            file = "qnn-turbo-$arch.zip",
            sizeBytes = sizeBytes,
            tier = DeviceTier.FLAGSHIP,
            multilingual = true,
            params = "809M",
            note = "NPU · Turbo fp16",
            explicitUrl = url,
            qnnArch = arch,
            socLabel = socLabel,
            labelOverride = "Whisper Turbo · NPU ($arch)",
        )
    }

    val qnnModels: List<ModelInfo> = all.filter { it.qnnArch != null }

    val byId: Map<String, ModelInfo> = all.associateBy { it.id }

    /** Default model per device tier. */
    val recommended: Map<DeviceTier, String> = mapOf(
        DeviceTier.LOW to "base-q8_0",
        DeviceTier.MID to "small-q8_0",
        DeviceTier.FLAGSHIP to "large-v3-turbo-q8_0",
    )

    /** Detects a QNN context-binary package entry (zip, extracted for the NPU engine). */
    fun isQnnPackage(modelId: String): Boolean = modelId.startsWith("qnn-turbo")

    /**
     * The model to use, in order: manual selection if downloaded → tier
     * recommendation if downloaded → any downloaded ggml model. Null only when
     * no ggml model is usable at all (QNN packages never run on the whisper.cpp
     * engine, so they don't count as a fallback here).
     */
    fun selectModel(settings: AppSettings, tier: DeviceTier, isDownloaded: (String) -> Boolean): ModelInfo? {
        settings.modelId?.let { id ->
            byId[id]?.let { if (isDownloaded(it.id)) return it }
        }
        recommendedFor(tier).let { if (isDownloaded(it.id)) return it }
        return all.firstOrNull { !isQnnPackage(it.id) && isDownloaded(it.id) }
    }

    fun recommendedFor(tier: DeviceTier): ModelInfo = byId.getValue(recommended.getValue(tier))

    fun forTier(tier: DeviceTier): List<ModelInfo> = all.filter { it.tier == tier }

    /**
     * Model choices offered at first launch, best first: the chip-matched NPU
     * Turbo package when the SoC has a supported Hexagon, then the tier
     * recommendation, then the lightest useful model.
     */
    fun onboardingOptions(tier: DeviceTier, hexagonArch: String?): List<ModelInfo> {
        val options = mutableListOf<ModelInfo>()
        if (hexagonArch != null) byId["qnn-turbo-$hexagonArch"]?.let { options.add(it) }
        val tierPick = recommendedFor(tier)
        if (options.none { it.id == tierPick.id }) options.add(tierPick)
        byId["small-q8_0"]?.let { if (options.none { o -> o.id == it.id }) options.add(it) }
        return options
    }
}
