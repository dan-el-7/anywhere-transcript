package com.anywhere.transcript.data

/** Device capability tiers, detected from total RAM (manual override in Settings). */
enum class DeviceTier(val label: String) {
    LOW("Low-end · 4 GB"),
    MID("Mid-range · 8 GB"),
    FLAGSHIP("Flagship · 16 GB");

    companion object {
        fun detect(totalRamBytes: Long): DeviceTier = when {
            totalRamBytes < 6L * GB -> LOW
            totalRamBytes < 12L * GB -> MID
            else -> FLAGSHIP
        }

        fun fromNameOrNull(name: String): DeviceTier? = entries.firstOrNull { it.name == name }

        private const val GB = 1024L * 1024 * 1024
    }
}
