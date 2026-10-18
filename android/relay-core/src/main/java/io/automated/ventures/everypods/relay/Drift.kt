package io.automated.ventures.everypods.relay

/**
 * Sender/receiver clock-drift correction. The sender's 48 kHz clock and the Pixel's output clock
 * differ by up to a few hundred ppm, so over a long session the jitter buffer slowly fills or
 * drains. We low-pass the buffer depth and steer a tiny resampling ratio (clamped ±[maxPpm]):
 * ratio > 1 consumes input faster (buffer too deep), < 1 slower.
 */
class DriftCorrector(
    private val maxPpm: Double = 2000.0,
    private val gainPpmPerMs: Double = 10.0,
    private val smoothing: Double = 0.01,
    private val deadbandMs: Double = 10.0,
) {
    private var ewma = Double.NaN
    var ratio = 1.0
        private set

    fun update(depthMs: Double, targetMs: Double): Double {
        ewma = if (ewma.isNaN()) depthMs else ewma + smoothing * (depthMs - ewma)
        val err = ewma - targetMs
        val e = if (kotlin.math.abs(err) < deadbandMs) 0.0 else err - kotlin.math.sign(err) * deadbandMs
        val ppm = (e * gainPpmPerMs).coerceIn(-maxPpm, maxPpm)
        ratio = 1.0 + ppm / 1_000_000.0
        return ratio
    }

    fun reset() { ewma = Double.NaN; ratio = 1.0 }
}

/** Streaming linear-interpolation resampler for interleaved PCM16; keeps phase across calls. */
class LinearResampler(private val channels: Int = RelayAudioFormat.CHANNELS) {
    private var pos = 0.0               // fractional read position relative to current block
    private val last = ShortArray(channels) // last frame of the previous block (index -1)
    private var primed = false

    fun process(input: ShortArray, ratio: Double): ShortArray {
        val frames = input.size / channels
        if (frames == 0) return ShortArray(0)
        if (ratio == 1.0 && pos == 0.0) {
            for (c in 0 until channels) last[c] = input[(frames - 1) * channels + c]
            primed = true
            return input.copyOf()
        }
        val out = ArrayList<Short>((frames / ratio).toInt() * channels + channels * 2)
        fun sample(i: Int, c: Int): Int = if (i < 0) (if (primed) last[c].toInt() else input[c].toInt()) else input[i * channels + c].toInt()
        // positions are relative to the block: frame -1 is the previous block's last frame
        var p = pos
        while (p <= frames - 1) {
            val i = kotlin.math.floor(p).toInt()
            val f = p - i
            for (c in 0 until channels) {
                val a = sample(i, c); val b = sample(minOf(i + 1, frames - 1), c)
                out += (a + (b - a) * f).toInt().coerceIn(-32768, 32767).toShort()
            }
            p += ratio
        }
        pos = p - frames
        for (c in 0 until channels) last[c] = input[(frames - 1) * channels + c]
        primed = true
        return out.toShortArray()
    }

    fun reset() { pos = 0.0; primed = false }
}
