package com.anywhere.transcript.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {

    @Test
    fun `same rate is passthrough`() {
        val input = floatArrayOf(0f, 0.25f, -0.5f, 1f)
        val out = Resampler.resample(input, 16000, 16000)
        assertEquals(input.size, out.size)
        input.forEachIndexed { i, v -> assertEquals(v, out[i], 1e-6f) }
    }

    @Test
    fun `downsample length is about rate ratio`() {
        val input = FloatArray(16000) { sin(2.0 * PI * 440.0 * it / 16000).toFloat() }
        val out = Resampler.resample(input, 44100, 16000)
        val expected = (input.size.toLong() * 16000 / 44100.0)
        assertEquals(expected, out.size.toDouble(), 2.0)
    }

    @Test
    fun `upsample length is about rate ratio`() {
        val input = FloatArray(1000) { it / 1000f }
        val out = Resampler.resample(input, 8000, 16000)
        assertEquals(1000.0 * 2, out.size.toDouble(), 2.0)
    }

    @Test
    fun `chunked processing matches one-shot processing`() {
        val input = FloatArray(30000) { sin(2.0 * PI * 220.0 * it / 44100).toFloat() }

        val oneShot = Resampler.resample(input, 44100, 16000)

        val conv = RateConverter(44100, 16000)
        val chunks = mutableListOf<FloatArray>()
        var i = 0
        while (i < input.size) {
            val end = minOf(i + 777, input.size)
            chunks.add(conv.process(input.copyOfRange(i, end)))
            i = end
        }
        chunks.add(conv.flush())
        val chunked = flatten(chunks)

        assertEquals(oneShot.size, chunked.size)
        oneShot.forEachIndexed { idx, v -> assertEquals(v, chunked[idx], 1e-4f) }
    }

    @Test
    fun `empty input produces empty output`() {
        assertTrue(Resampler.resample(FloatArray(0), 44100, 16000).isEmpty())
        RateConverter(44100, 16000).flush().let { assertEquals(0, it.size) }
    }

    private fun flatten(parts: List<FloatArray>): FloatArray {
        val all = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (c in parts) {
            System.arraycopy(c, 0, all, p, c.size)
            p += c.size
        }
        return all
    }
}
