package com.anywhere.transcript.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTierTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `detects low tier below 6GB`() {
        assertEquals(DeviceTier.LOW, DeviceTier.detect(4L * gb))
        assertEquals(DeviceTier.LOW, DeviceTier.detect(5L * gb + 999))
    }

    @Test
    fun `detects mid tier from 6GB to below 12GB`() {
        assertEquals(DeviceTier.MID, DeviceTier.detect(6L * gb))
        assertEquals(DeviceTier.MID, DeviceTier.detect(8L * gb))
        assertEquals(DeviceTier.MID, DeviceTier.detect(11L * gb + 512L * 1024 * 1024))
    }

    @Test
    fun `detects flagship from 12GB`() {
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect(12L * gb))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.detect(16L * gb))
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
