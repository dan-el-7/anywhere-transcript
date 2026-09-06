package com.anywhere.transcript.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit 16 kHz mono little-endian PCM into a RIFF/WAVE file,
 * patching the RIFF/data sizes on close (a streaming writer can't know the
 * length up front). The result is a plain, universally parseable WAV that
 * [WavReader] and the Transcribe flow accept as-is.
 */
class WavWriter(private val output: File) : Closeable {

    private val out: OutputStream = FileOutputStream(output)
    private var frames = 0L
    private var closed = false

    init {
        out.write(header(0)) // placeholder; sizes patched on close
    }

    /** Appends int16 mono PCM samples. */
    fun write(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        val buf = ByteArray(count * 2)
        var p = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            buf[p++] = (v and 0xFF).toByte()
            buf[p++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        frames += count
    }

    /** Appends [-1,1] mono floats, converted to int16 PCM. */
    fun writeFloats(samples: FloatArray, count: Int = samples.size) {
        if (count <= 0) return
        val buf = ByteArray(count * 2)
        var p = 0
        for (i in 0 until count) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            buf[p++] = (v and 0xFF).toByte()
            buf[p++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        frames += count
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
        val dataBytes = frames * 2
        RandomAccessFile(output, "rw").use { r ->
            // RandomAccessFile.writeInt is big-endian; WAV needs LE bytes
            fun le(off: Long, v: Int) {
                r.seek(off)
                r.write(byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 24) and 0xFF).toByte(),
                ))
            }
            le(4, (36 + dataBytes).toInt()) // RIFF size
            le(40, dataBytes.toInt())       // data size
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val h = ByteArray(44)
        fun le(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun tag(off: Int, s: String) = s.forEachIndexed { i, c -> h[off + i] = c.code.toByte() }
        tag(0, "RIFF")
        le(4, (36 + dataBytes).toInt())
        tag(8, "WAVE")
        tag(12, "fmt ")
        le(16, 16)          // fmt chunk size
        le(20, 1)           // PCM
        le(22, 1)           // mono
        le(24, SAMPLE_RATE)
        le(28, SAMPLE_RATE * 2) // byte rate
        le(32, 2)           // block align
        le(34, 16)          // bits per sample
        tag(36, "data")
        le(40, dataBytes.toInt())
        return h
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
