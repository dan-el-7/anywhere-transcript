package com.anywhere.transcript.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTierTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `detects low tier for 3-4GB devices`() {
        // what 3/4 GB phones actually report (totalMem under-reports marketing)
        assertEquals(DeviceTier.LOW, DeviceTier.detect((2.7 * gb).toLong()))
        assertEquals(DeviceTier.LOW, DeviceTier.detect((3.6 * gb).toLong()))
        assertEquals(DeviceTier.LOW, DeviceTier.detect(4L * gb))
    }

    @Test
    fun `detects mid tier for 6-8GB devices`() {
        // a "6 GB" phone reports ~5.4 GB and must still land MID
        assertEquals(DeviceTier.MID, DeviceTier.detect((5.4 * gb).toLong()))
        assertEquals(DeviceTier.MID, DeviceTier.detect(6L * gb))
        assertEquals(DeviceTier.MID, DeviceTier.detect((7.2 * gb).toLong()))
        assertEquals(DeviceTier.MID, DeviceTier.detect(8L * gb))
    }

    @Test
    fun `detects flagship from 12GB marketing (about 11GB reported)`() {
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect((10.9 * gb).toLong()))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect(12L * gb))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect(16L * gb))
    }

    @Test
    fun `raw reported-byte boundaries are exact`() {
        // normalization ×10/9 flips approxGB 4→5 at 4.5 GiB and 8→9 at 8.1 GiB
        val lowMid = 9L * gb / 2            // 4.5 GiB
        assertEquals(DeviceTier.LOW, DeviceTier.detect(lowMid - 1))
        assertEquals(DeviceTier.MID, DeviceTier.detect(lowMid))
        val midTop = 9L * (9L * gb) / 10    // 8.1 GiB
        assertEquals(DeviceTier.MID, DeviceTier.detect(midTop))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect(midTop + 1))
    }

    @Test
    fun `round trips from name`() {
        DeviceTier.entries.forEach { tier ->
            assertEquals(tier, DeviceTier.fromNameOrNull(tier.name))
        }
        assertEquals(null, DeviceTier.fromNameOrNull("auto"))
        assertEquals(null, DeviceTier.fromNameOrNull("bogus"))
    }
}
