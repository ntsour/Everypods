package io.automated.ventures.everypods.relay

import kotlin.math.abs

/**
 * Adaptive receiver jitter buffer for fixed-duration media frames, driven by the playout clock:
 * call [pull] once per output frame (the AudioTrack's blocking write paces it).
 *
 * - Starts at [startMs] target delay; adapts within [minMs]..[maxMs] from an RFC 3550-style
 *   inter-arrival jitter estimate. Grows fast (immediately on underrun), shrinks slowly.
 * - On underrun it re-buffers to the target instead of glitching repeatedly.
 * - Late (already played) and duplicate packets are dropped and counted.
 * - A gap whose later packets have arrived is reported as [Pull.Lost] (PLC / FEC).
 * - If depth exceeds [maxMs] + [trimSlackMs] (e.g. after a burst) oldest frames are trimmed.
 *
 * Not thread-safe; callers synchronize (one lock around offer/pull).
 */
class JitterBuffer(
    val frameMs: Int = RelayAudioFormat.FRAME_MS,
    val startMs: Int = 200,
    val minMs: Int = 100,
    val maxMs: Int = 500,
    private val trimSlackMs: Int = 100,
    private val capacityFrames: Int = 256,
) {
    sealed class Pull {
        data class Frame(val seq: Long, val timestamp: Long, val payload: ByteArray) : Pull()
        /** [fecFrom] is the next packet's payload when available (Opus in-band FEC). */
        data class Lost(val seq: Long, val fecFrom: ByteArray?) : Pull()
        /** Not playing yet / re-buffering: output silence. */
        object Buffering : Pull()
    }

    private class Entry(val timestamp: Long, val payload: ByteArray)

    private val store = java.util.TreeMap<Long, Entry>()
    var playing = false
        private set
    private var nextSeq = -1L
    private var prevTransit = Double.NaN
    var jitterMs = 0.0
        private set
    var targetMs = startMs.toDouble()
        private set

    var received = 0L; private set
    var late = 0L; private set
    var duplicates = 0L; private set
    var lost = 0L; private set
    var underruns = 0L; private set
    var trimmed = 0L; private set

    fun depthFrames(): Int {
        if (store.isEmpty()) return 0
        val from = if (playing) nextSeq else store.firstKey()
        return (store.lastKey() - from + 1).coerceAtLeast(0).toInt()
    }

    fun depthMs(): Int = depthFrames() * frameMs

    fun offer(seq: Long, timestamp: Long, payload: ByteArray, arrivalMs: Double) {
        if (playing && seq < nextSeq) { late++; return }
        if (store.containsKey(seq)) { duplicates++; return }
        val anchor = if (playing) nextSeq else store.firstEntry()?.key ?: seq
        if (seq - anchor >= capacityFrames || anchor - seq >= capacityFrames) {
            // Sender restarted or long outage: start over rather than wait for a huge gap.
            flush()
        }
        received++
        store[seq] = Entry(timestamp, payload)
        val transit = arrivalMs - seq * frameMs.toDouble()
        if (!prevTransit.isNaN()) jitterMs += (abs(transit - prevTransit) - jitterMs) / 16.0
        prevTransit = transit
    }

    fun pull(): Pull {
        adaptTarget()
        if (!playing) {
            if (store.isEmpty() || depthMs() < targetMs) return Pull.Buffering
            playing = true
            nextSeq = store.firstKey()
        }
        trimIfTooDeep()
        val e = store.remove(nextSeq)
        if (e != null) return Pull.Frame(nextSeq++, e.timestamp, e.payload)
        if (store.isNotEmpty() && store.lastKey() > nextSeq) {
            lost++
            val fec = store[nextSeq + 1]?.payload
            return Pull.Lost(nextSeq++, fec)
        }
        underruns++
        playing = false
        targetMs = (targetMs + 2 * frameMs).coerceAtMost(maxMs.toDouble())
        return Pull.Buffering
    }

    private fun adaptTarget() {
        val desired = (4 * jitterMs + 2 * frameMs).coerceIn(minMs.toDouble(), maxMs.toDouble())
        targetMs = if (desired > targetMs) desired else (targetMs - 0.05).coerceAtLeast(desired)
    }

    private fun trimIfTooDeep() {
        while (depthMs() > maxMs + trimSlackMs && store.isNotEmpty()) {
            val first = store.firstKey()
            store.remove(first)
            nextSeq = maxOf(nextSeq, first + 1)
            trimmed++
        }
        if (store.isNotEmpty() && nextSeq < store.firstKey() && depthMs() > maxMs + trimSlackMs) nextSeq = store.firstKey()
    }

    /** Stop / source change: drop everything queued; next audio re-buffers. */
    fun flush() {
        store.clear(); playing = false; nextSeq = -1; prevTransit = Double.NaN
    }
}
