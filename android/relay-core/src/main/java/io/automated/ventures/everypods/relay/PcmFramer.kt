package io.automated.ventures.everypods.relay

/** Accumulates arbitrary interleaved PCM reads into exact codec frames. */
class PcmFramer(private val frameShorts: Int = RelayAudioFormat.FRAME_SAMPLES * RelayAudioFormat.CHANNELS) {
    private val buf = ShortArray(frameShorts)
    private var fill = 0

    fun push(data: ShortArray, count: Int = data.size, onFrame: (ShortArray) -> Unit) {
        var off = 0
        while (off < count) {
            val n = minOf(frameShorts - fill, count - off)
            System.arraycopy(data, off, buf, fill, n)
            fill += n; off += n
            if (fill == frameShorts) { onFrame(buf.copyOf()); fill = 0 }
        }
    }

    fun reset() { fill = 0 }
}

/**
 * NTP-style offset estimate from control keepalives: receiver sends t0, sender echoes with its
 * own clock t1, receiver gets it back at t2. offset = sender − receiver ≈ t1 − (t0 + t2)/2.
 * Keeps the sample with the smallest RTT (least queuing) over a sliding window.
 */
class ClockSync(private val window: Int = 16) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // rtt, offset (micros)

    fun add(t0: Long, t1: Long, t2: Long) {
        val rtt = t2 - t0
        if (rtt < 0) return
        samples.addLast(rtt to (t1 - (t0 + t2) / 2))
        while (samples.size > window) samples.removeFirst()
    }

    val offsetMicros: Long? get() = samples.minByOrNull { it.first }?.second
    val rttMicros: Long? get() = samples.minByOrNull { it.first }?.first
}

/** DEV ONLY (P2): pre-shared test key, mixed into HKDF to authenticate the handshake. Removed in P3. */
object DevPsk {
    fun fromPassphrase(text: String): ByteArray {
        require(text.length >= 8) { "test key must be at least 8 characters" }
        return Hkdf.derive("EveryPods relay dev psk".toByteArray(), text.toByteArray(Charsets.UTF_8), "psk".toByteArray(), 32)
    }

    fun sessionKeys(dh: ByteArray, psk: ByteArray, sourceEph: ByteArray, receiverEph: ByteArray) =
        RelaySessionKeys.derive(listOf(dh, psk), RelaySessionKeys.transcript(sourceEph, receiverEph))
}
