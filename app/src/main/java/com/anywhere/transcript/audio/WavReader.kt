package com.anywhere.transcript.audio

import java.io.IOException
import java.io.InputStream

/**
 * Minimal RIFF/WAVE parser (PCM 8/16/24-bit and IEEE float 32), JVM-testable.
 * Supports multi-channel files by mixing down to mono.
 */
object WavReader {

    data class Header(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    }

    data class WavData(val mono: FloatArray, val sampleRate: Int)

    /** Sniffs and parses the header; stream is left positioned at the data chunk. */
    fun parseHeader(input: InputStream): Header? {
        try {
            val riff = ByteArray(12)
            if (input.read(riff) != riff.size) return null
            if (!riff.copyOfRange(0, 4).decodeToString().equals("RIFF", true)) return null
            if (!riff.copyOfRange(8, 12).decodeToString().equals("WAVE", true)) return null

            var header: Header? = null
            var pendingDataBytes = -1L
            while (true) {
                val chunkHeader = ByteArray(8)
                val read = input.readNBytesCompat(chunkHeader, 8)
                if (read < 8) break
                val chunkId = chunkHeader.copyOfRange(0, 4).decodeToString()
                val chunkSize = readLeInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL

                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize.toInt().coerceAtMost(64))
                        if (input.readNBytesCompat(fmt, fmt.size) < fmt.size) return null
                        val audioFormat = readLeShort(fmt, 0)
                        val channels = readLeShort(fmt, 2).toInt()
                        val sampleRate = readLeInt(fmt, 4)
                        val bits = readLeShort(fmt, 14).toInt()
                        if (channels <= 0 || sampleRate <= 0 || bits % 8 != 0) return null
                        val isFloat = audioFormat == 3 || (audioFormat == 0xFFFE && bits == 32)
                        if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xFFFE) return null
                        header = Header(sampleRate, channels, bits, isFloat, 0, 0)
                    }
                    "data" -> {
                        pendingDataBytes = chunkSize
                        // dataOffset is current position; need fmt first — RIFF guarantees fmt before data in practice,
                        // but if data came first we cannot know the format: bail.
                        val h = header ?: return null
                        return h.copy(dataOffset = 0, dataBytes = pendingDataBytes)
                    }
                    else -> {
                        var skipped = 0L
                        val buf = ByteArray(4096)
                        while (skipped < chunkSize) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), chunkSize - skipped).toInt())
                            if (n < 0) return null
                            skipped += n
                        }
                    }
                }
            }
            return null
        } catch (e: IOException) {
            return null
        }
    }

    /** Decodes at most [frames] frames from a data stream into mono floats. */
    fun decodeChunk(header: Header, input: InputStream, frames: Int): FloatArray {
        val bytesPerFrame = header.bytesPerFrame
        val toRead = frames * bytesPerFrame
        val bytes = ByteArray(toRead)
        val total = input.readNBytesCompat(bytes, toRead)
        if (total == 0) return FloatArray(0)
        val frameCount = total / bytesPerFrame
        val mono = FloatArray(frameCount)
        val frameBytes = ByteArray(bytesPerFrame)
        var pos = 0
        for (f in 0 until frameCount) {
            var acc = 0f
            for (c in 0 until header.channels) {
                acc += decodeSample(header, bytes, pos + c * (header.bitsPerSample / 8))
            }
            mono[f] = acc / header.channels
            pos += bytesPerFrame
        }
        return mono
    }

    /** Full read (tests / small files). */
    fun readAll(input: InputStream): WavData? {
        val header = parseHeader(input) ?: return null
        val parts = ArrayList<FloatArray>()
        while (true) {
            val chunk = decodeChunk(header, input, 8192)
            if (chunk.isEmpty()) break
            parts.add(chunk)
        }
        val size = parts.sumOf { it.size }
        val mono = FloatArray(size)
        var p = 0
        for (c in parts) {
            System.arraycopy(c, 0, mono, p, c.size)
            p += c.size
        }
        return WavData(mono, header.sampleRate)
    }

    private fun decodeSample(h: Header, b: ByteArray, off: Int): Float = when (h.bitsPerSample) {
        8 -> (((b[off].toInt() and 0xFF) - 128) / 128f)
        16 -> {
            val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
            (v.toShort().toInt()) / 32768f
        }
        24 -> {
            val v = ((b[off].toInt() and 0xFF)) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16)
            val signed = if (v and 0x800000 != 0) v or 0xFF000000.toInt() else v
            signed / 8388608f
        }
        32 -> if (h.isFloat) {
            java.lang.Float.intBitsToFloat(readLeInt(b, off))
        } else {
            readLeInt(b, off) / 2147483648f
        }
        else -> 0f
    }

    private fun readLeShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** InputStream.readNBytes exists since Java 9 / Android API 33; provide our own. */
    private fun InputStream.readNBytesCompat(target: ByteArray, len: Int): Int {
        var filled = 0
        while (filled < len) {
            val n = read(target, filled, len - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }
}
