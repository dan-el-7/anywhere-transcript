package com.anywhere.transcript.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class WavWriterTest {

    @Test
    fun `streamed wav round-trips through WavReader`() {
        val tmp = File.createTempFile("wavw", ".wav")
        try {
            val samples = ShortArray(16000 * 3) { (it % 1000).toShort() } // 3 s
            // streamed via writeFloats + write to cover both paths
            WavWriter(tmp).use { w ->
                w.write(samples, 16000)          // first second, int16 path
                val floats = FloatArray(16000 * 2) { i -> samples[16000 + i] / 32768f }
                w.writeFloats(floats, 8000)       // second, float path
                w.writeFloats(floats, 8000)       // flush rest
                w.writeFloats(floats.copyOfRange(16000, 32000)) // third
            }

            val data = WavReader.readAll(ByteArrayInputStream(tmp.readBytes()))
            assertEquals(16000, data?.sampleRate)
            assertEquals(samples.size, data?.mono?.size)
            samples.forEachIndexed { i, s ->
                assertEquals(s / 32768f, data!!.mono[i], 1f / 32767f)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `header declares 16khz mono pcm16 with patched sizes`() {
        val tmp = File.createTempFile("wavh", ".wav")
        try {
            WavWriter(tmp).use { it.write(ShortArray(32) { 0 }) }
            val bytes = tmp.readBytes()
            val h = WavReader.parseHeader(ByteArrayInputStream(bytes))!!
            assertEquals(16000, h.sampleRate)
            assertEquals(1, h.channels)
            assertEquals(16, h.bitsPerSample)
            assertEquals(64L, h.dataBytes)
            // RIFF size = 36 + dataBytes (LE at offset 4)
            val riff = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            assertEquals(100, riff)
        } finally {
            tmp.delete()
        }
    }
}
