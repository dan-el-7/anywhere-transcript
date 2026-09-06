package com.anywhere.transcript.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Decodes any share-in audio (or video) file into windows of mono 16 kHz float PCM
 * suitable for whisper.cpp. Uses WavReader for plain WAV files and
 * MediaExtractor/MediaCodec for everything else (mp3/m4a/aac/flac/ogg/mp4/webm…).
 *
 * Memory stays bounded: output is produced window-by-window, and the internal
 * 16 kHz accumulator holds at most one window beyond what the caller consumed.
 */
class AudioDecoder(private val context: Context) {

    data class Opened(val durationMs: Long, val mime: String)

    // MediaCodec path
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var mime: String = ""

    // WAV fast path
    private var wavStream: DataInputStream? = null
    private var wavHeader: WavReader.Header? = null

    private var inputEos = false
    private var outputEos = false
    private var flushed = false
    private var durationMs = -1L
    private var sourceRate = 16000
    private var sourceChannels = 1
    private var pcmFloat = false

    private var converter: RateConverter? = null
    private var out16k = FloatArray(0)

    fun open(uri: Uri): Opened {
        reset()
        val sniff = try {
            context.contentResolver.openInputStream(uri)?.use { sniffWav(it) } ?: false
        } catch (t: Throwable) {
            false
        }
        return if (sniff) openWav(uri) else openMedia(uri)
    }

    private fun sniffWav(raw: InputStream): Boolean {
        val head = ByteArray(12)
        var filled = 0
        while (filled < 12) {
            val n = raw.read(head, filled, 12 - filled)
            if (n < 0) break
            filled += n
        }
        if (filled < 12) return false
        return String(head, 0, 4, Charsets.US_ASCII).equals("RIFF", true) &&
            String(head, 8, 4, Charsets.US_ASCII).equals("WAVE", true)
    }

    private fun openWav(uri: Uri): Opened {
        val stream = DataInputStream(BufferedInputStream(context.contentResolver.openInputStream(uri)))
        val header = WavReader.parseHeader(stream)
            ?: throw IOException("Unsupported or corrupt WAV file")
        wavStream = stream
        wavHeader = header
        sourceRate = header.sampleRate
        sourceChannels = header.channels
        pcmFloat = header.isFloat
        mime = "audio/wav"
        return Opened(durationMs = header.dataBytes / header.bytesPerFrame * 1000L / header.sampleRate, mime = mime)
    }

    private fun openMedia(uri: Uri): Opened {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context.applicationContext, uri, null)
        } catch (t: Throwable) {
            ex.release()
            throw IOException("Could not open this file (${t.message ?: "unknown error"})")
        }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) {
                trackIndex = i
                format = f
                mime = m
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            ex.release()
            throw IOException("No audio track found in this file")
        }
        ex.selectTrack(trackIndex)
        durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION) / 1000
        } else -1L
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

        extractor = ex
        try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
        } catch (t: Throwable) {
            ex.release()
            extractor = null
            throw IOException("No decoder available for $mime (${t.message ?: "unknown error"})")
        }
        return Opened(durationMs = durationMs, mime = mime)
    }

    /**
     * Blocking: decodes until a window of ~windowSec seconds is available or the
     * stream ends. Returns null when the file is fully consumed or [isCancelled]
     * turns true (long decodes must stay cancellable).
     */
    fun nextWindow(windowSec: Int, isCancelled: () -> Boolean = { false }): FloatArray? {
        require(windowSec > 0)
        val need = windowSec * 16000
        val conv = converter ?: RateConverter(sourceRate, 16000).also { converter = it }

        while (out16k.size < need && !eosReached()) {
            if (isCancelled()) break
            decodeStep()
            if (eosReached() && !flushed) {
                flushed = true
                append16k(conv.flush())
            }
        }
        if (isCancelled() && out16k.size < need) return null
        val take = minOf(need, out16k.size)
        if (take == 0) return null
        val window = out16k.copyOf(take)
        out16k = if (take == out16k.size) FloatArray(0) else out16k.copyOfRange(take, out16k.size)
        return window
    }

    fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        extractor?.release()
        extractor = null
        try {
            wavStream?.close()
        } catch (_: Exception) {
        }
        wavStream = null
        wavHeader = null
        out16k = FloatArray(0)
        converter = null
    }

    // ---- internals ---------------------------------------------------------------

    private fun eosReached(): Boolean = inputEos && outputEos

    private fun append16k(samples: FloatArray) {
        if (samples.isEmpty()) return
        val merged = FloatArray(out16k.size + samples.size)
        System.arraycopy(out16k, 0, merged, 0, out16k.size)
        System.arraycopy(samples, 0, merged, out16k.size, samples.size)
        out16k = merged
    }

    private fun reset() {
        close()
        inputEos = false
        outputEos = false
        flushed = false
        durationMs = -1L
        sourceRate = 16000
        sourceChannels = 1
        pcmFloat = false
        mime = ""
    }

    private fun decodeStep() {
        if (wavStream != null && wavHeader != null) {
            decodeWavStep()
        } else {
            decodeCodecStep()
        }
    }

    private fun decodeWavStep() {
        val header = wavHeader ?: run {
            outputEos = true
            inputEos = true
            return
        }
        val stream = wavStream!!
        val mono = WavReader.decodeChunk(header, stream, 8192)
        if (mono.isEmpty()) {
            inputEos = true
            outputEos = true
            return
        }
        append16k(converterOrNew().process(mono))
    }

    private fun decodeCodecStep() {
        val c = codec ?: run {
            outputEos = true
            inputEos = true
            return
        }
        val ex = extractor ?: run {
            outputEos = true
            inputEos = true
            return
        }

        if (!inputEos) {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx)!!
                val size = ex.readSampleData(ib, 0)
                if (size < 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEos = true
                } else {
                    c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                    ex.advance()
                }
            }
        }

        val info = MediaCodec.BufferInfo()
        val outIdx = c.dequeueOutputBuffer(info, 10_000)
        when {
            outIdx >= 0 -> {
                if (info.size > 0) {
                    val ob = c.getOutputBuffer(outIdx)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val mono = if (pcmFloat) {
                        val fb = ob.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val n = fb.remaining() / sourceChannels
                        val m = FloatArray(n)
                        val tmp = FloatArray(sourceChannels)
                        for (i in 0 until n) {
                            fb.get(tmp)
                            var acc = 0f
                            for (v in tmp) acc += v
                            m[i] = acc / sourceChannels
                        }
                        m
                    } else {
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val n = sb.remaining() / sourceChannels
                        val m = FloatArray(n)
                        val tmp = ShortArray(sourceChannels)
                        for (i in 0 until n) {
                            sb.get(tmp)
                            var acc = 0
                            for (v in tmp) acc += v.toInt()
                            m[i] = acc.toFloat() / sourceChannels / 32768f
                        }
                        m
                    }
                    append16k(converterOrNew().process(mono))
                }
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputEos = true
                }
            }
            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = c.outputFormat
                val newRate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sourceRate
                val newCh = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else sourceChannels
                val newFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                if (newRate != sourceRate && converter != null) {
                    append16k(converter!!.flush())
                    converter = RateConverter(newRate, 16000)
                }
                sourceRate = newRate
                sourceChannels = newCh
                pcmFloat = newFloat
            }
            // INFO_TRY_AGAIN_LATER: just return; caller loops until EOS.
        }
    }

    private fun converterOrNew(): RateConverter = converter ?: RateConverter(sourceRate, 16000).also { converter = it }
}
