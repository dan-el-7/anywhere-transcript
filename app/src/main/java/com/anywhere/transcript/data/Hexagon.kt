package com.anywhere.transcript.data

import android.os.Build

/**
 * Maps the Android-reported SoC model to the Hexagon DSP architecture it carries.
 * QNN context binaries are arch-locked — a binary compiled for v79 only loads on
 * a v79 DSP — so downloads must match the chip, not the other way around.
 */
object Hexagon {

    /** SoC model as reported by Android (e.g. "SM8750"); API 31+, null when unknown. */
    fun socModel(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.takeIf { it.isNotBlank() } else null

    /** Hexagon arch for an SoC model string, or null for unknown / non-Qualcomm chips. */
    fun archForSoc(soc: String?): String? {
        val s = soc?.uppercase() ?: return null
        // substring match: Galaxy-specific silicon carries suffixed model ids
        // (SM8750-AC, SM8750-3-NZAE, …) that must still map to the base arch
        return when {
            "SM8450" in s || "SM8475" in s -> "v69"  // 8 Gen 1 / 8+ Gen 1
            "SM8550" in s || "SM8635" in s || "SM7675" in s || "QCS8550" in s || "QCM8550" in s ->
                "v73"                                 // 8 Gen 2 / 8s Gen 3 / 7+ Gen 3
            "SM8650" in s -> "v75"                    // 8 Gen 3
            "SM8750" in s -> "v79"                    // 8 Elite (incl. -AC Galaxy variants)
            "SM8850" in s -> "v81"                    // 8 Elite Gen 5
            else -> null
        }
    }

    /** This device's Hexagon arch, or null when the chip is unknown. */
    fun deviceArch(): String? = archForSoc(socModel())
}
