package com.anywhere.transcript.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WavReaderTest {

    @Test
    fun `parses 16-bit stereo wav and mixes down to mono`() {
        val sampleRate = 8000
        val channels = 2
        val frames = 1000
        val samples = ShortArray(frames * channels) { idx ->
            // left channel increments, right channel is constant
            if (idx % 2 == 0) ((idx / 2 % 1000) * 32 - 16000).toShort() else 1000
        }
        val wav = buildWav(sampleRate, channels, 16, samples)

        val data = WavReader.readAll(ByteArrayInputStream(wav))!!
        assertEquals(sampleRate, data.sampleRate)
        assertEquals(frames, data.mono.size)

        // frame 0: left=-16000/32768, right=1000/32768 → mono = (-16000 + 1000)/2/32768
        val expected0 = (-16000f / 32768f + 1000f / 32768f) / 2f
        assertEquals(expected0, data.mono[0], 1e-4f)
    }

    @Test
    fun `rejects non-wav data`() {
        assertNull(WavReader.readAll(ByteArrayInputStream(ByteArray(64))))
        assertNull(WavReader.readAll(ByteArrayInputStream("OggS not a wav".toByteArray())))
    }

    @Test
    fun `decodes chunk boundaries cleanly`() {
        val sampleRate = 16000
        val frames = 5000
        val samples = ShortArray(frames) { (it % 32767).toShort() }
        val wav = buildWav(sampleRate, 1, 16, samples)
        val header = WavReader.parseHeader(ByteArrayInputStream(wav))!!
        val stream = ByteArrayInputStream(wav)
        // skip header
        WavReader.parseHeader(stream)

        var decoded = 0
        while (true) {
            val chunk = WavReader.decodeChunk(header, stream, 8192)
            if (chunk.isEmpty()) break
            decoded += chunk.size
        }
        assertEquals(frames, decoded)
    }

    /** Minimal canonical RIFF writer for tests. */
    private fun buildWav(sampleRate: Int, channels: Int, bits: Int, samples: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        val bytesPerSample = bits / 8
        val dataBytes = samples.size * bytesPerSample
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun le32(v: Int) {
            le16(v and 0xFFFF)
            le16((v shr 16) and 0xFFFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(channels)
        le32(sampleRate)
        le32(sampleRate * channels * bytesPerSample)
        le16(channels * bytesPerSample)
        le16(bits)
        out.write("data".toByteArray())
        le32(dataBytes)
        samples.forEach { s -> le16(s.toInt() and 0xFFFF) }
        return out.toByteArray()
    }
}
