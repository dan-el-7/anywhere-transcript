package com.anywhere.transcript.audio

/**
 * Streaming linear resampler. Keeps input buffer state across process() calls so
 * arbitrary chunk boundaries are safe. Designed for speech ASR (16 kHz target),
 * where linear interpolation is the same approach whisper.cpp's own tooling uses.
 */
class RateConverter(private val fromRate: Int, private val toRate: Int) {

    init {
        require(fromRate > 0 && toRate > 0) { "invalid rates $fromRate -> $toRate" }
    }

    private var buf = FloatArray(0)
    private var baseIdx = 0L // global input index of buf[0]
    private var outIdx = 0L  // global output index of the next sample

    private fun inPos(outIndex: Long): Double = outIndex.toDouble() * fromRate / toRate

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (fromRate == toRate) {
            outIdx += input.size
            baseIdx += input.size
            return input.copyOf()
        }
        buf = concat(buf, input)
        val end = baseIdx + buf.size // exclusive global end of buffered input

        // outputs whose interpolation window [idx, idx+1] is fully buffered
        var n = 0L
        while (inPos(outIdx + n).toLong() + 1 <= end - 1) n++

        val out = FloatArray(n.toInt())
        for (i in out.indices) {
            val t = inPos(outIdx + i)
            val idx = t.toLong() // floor (t >= 0)
            val frac = (t - idx).toFloat()
            val i0 = (idx - baseIdx).toInt()
            val s0 = buf[i0]
            val s1 = buf[i0 + 1]
            out[i] = s0 + (s1 - s0) * frac
        }
        outIdx += n

        // drop consumed input samples, keep from the next output position onward
        val keepFrom = inPos(outIdx).toLong()
        val drop = (keepFrom - baseIdx).toInt().coerceIn(0, buf.size)
        if (drop > 0) {
            buf = buf.copyOfRange(drop, buf.size)
            baseIdx += drop
        }
        return out
    }

    /** Flushes everything still convertible; call once at end of stream. */
    fun flush(): FloatArray {
        val out = process(FloatArray(0))
        buf = FloatArray(0)
        return out
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        if (a.isEmpty()) return b.copyOf()
        val r = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        return r
    }
}

object Resampler {
    /** Convenience one-shot resample of a whole buffer. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty()) return input
        val conv = RateConverter(fromRate, toRate)
        val head = conv.process(input)
        val tail = conv.flush()
        if (tail.isEmpty()) return head
        val all = FloatArray(head.size + tail.size)
        System.arraycopy(head, 0, all, 0, head.size)
        System.arraycopy(tail, 0, all, head.size, tail.size)
        return all
    }
}
