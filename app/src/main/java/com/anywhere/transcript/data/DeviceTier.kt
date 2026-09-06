package com.anywhere.transcript.data

/** Device capability tiers, detected from total RAM (manual override in Settings). */
enum class DeviceTier(val label: String) {
    LOW("Low-end · 4 GB"),
    MID("Mid-range · 8 GB"),
    FLAGSHIP("Flagship · 16 GB");

    companion object {
        /**
         * MemoryInfo.totalMem under-reports the spec-sheet figure (kernel and
         * vendor partitions reserve ~5-10%: a "6 GB" phone reports ~5.4 GB),
         * so normalize back to marketing GB before bucketing. Effective raw
         * thresholds: LOW below 4.5 GiB reported, MID below 8.1 GiB.
         */
        fun detect(totalRamBytes: Long): DeviceTier {
            // ÷0.9 in exact integer math (×10/9) — float division rounds the
            // wrong way at exact boundaries
            val approxMarketingGB = totalRamBytes * 10 / (9L * GB)
            return when {
                approxMarketingGB <= 4 -> LOW   // 3-4 GB devices
                approxMarketingGB <= 8 -> MID   // 6-8 GB devices
                else -> FLAGSHIP                // 12 GB and up
            }
        }

        fun fromNameOrNull(name: String): DeviceTier? = entries.firstOrNull { it.name == name }

        private const val GB = 1024L * 1024 * 1024
    }
}
