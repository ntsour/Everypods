/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned media envelope carried only inside the authenticated DTLS data channel. */
data class RelayMediaPacket(
    val sequence: Long,
    val timestampNanos: Long,
    val durationMillis: Int,
    val opusPayload: ByteArray,
) {
    fun encode(): ByteArray {
        require(sequence in 0..0xffff_ffffL) { "Sequence outside uint32 range" }
        require(timestampNanos >= 0) { "Negative sender timestamp" }
        require(durationMillis in SUPPORTED_FRAME_DURATIONS) { "Unsupported frame duration" }
        require(opusPayload.isNotEmpty() && opusPayload.size <= MAX_PAYLOAD_BYTES) { "Invalid Opus payload size" }
        return ByteBuffer.allocate(HEADER_BYTES + opusPayload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .putInt(sequence.toInt())
            .putLong(timestampNanos)
            .putShort(durationMillis.toShort())
            .putShort(opusPayload.size.toShort())
            .put(opusPayload)
            .array()
    }

    companion object {
        private const val MAGIC = 0x45505231 // EPR1
        private const val VERSION = 1
        private const val HEADER_BYTES = 21
        const val FRAME_DURATION_MS = 20
        val SUPPORTED_FRAME_DURATIONS = setOf(10, 20)
        const val MAX_PAYLOAD_BYTES = 4_096

        fun decode(encoded: ByteArray, expectedDurationMillis: Int? = null): RelayMediaPacket {
            require(encoded.size in (HEADER_BYTES + 1)..(HEADER_BYTES + MAX_PAYLOAD_BYTES)) { "Invalid packet length" }
            val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            require(input.int == MAGIC) { "Invalid media packet magic" }
            require(input.get().toInt() and 0xff == VERSION) { "Unsupported media packet version" }
            val sequence = input.int.toLong() and 0xffff_ffffL
            val timestamp = input.long
            val duration = input.short.toInt() and 0xffff
            val payloadLength = input.short.toInt() and 0xffff
            require(timestamp >= 0) { "Invalid sender timestamp" }
            require(duration in SUPPORTED_FRAME_DURATIONS && (expectedDurationMillis == null || duration == expectedDurationMillis)) { "Unsupported frame duration" }
            require(payloadLength in 1..MAX_PAYLOAD_BYTES && payloadLength == input.remaining()) { "Invalid Opus payload length" }
            return RelayMediaPacket(sequence, timestamp, duration, ByteArray(payloadLength).also(input::get))
        }
    }
}

/**
 * Bounded packet reorder buffer. It starts at its configured target and never grows
 * beyond the selected ceiling. Missing frames are surfaced so the
 * Opus decoder can apply packet-loss concealment instead of replaying stale audio.
 */
class RelayJitterBuffer(
    private val initialTargetDelayMillis: Int = 200,
    private val maximumDelayMillis: Int = 500,
    private val frameDurationMillis: Int = 20,
    private val adaptiveMode: Int = 0,
) {
    sealed interface Output {
        data class Frame(val packet: RelayMediaPacket) : Output
        data class Missing(val sequence: Long, val timestampNanos: Long) : Output
    }

    data class Statistics(
        val accepted: Long,
        val rejected: Long,
        val concealed: Long,
        val refills: Long,
        val queued: Int,
        val targetDelayMillis: Int,
    )

    private val frames = java.util.TreeMap<Long, RelayMediaPacket>()
    private var expectedSequence: Long? = null
    private var firstSequence: Long? = null
    private var playoutStartsAtMillis: Long? = null
    private var lastTimestampNanos = 0L
    private var currentTargetDelayMillis = initialTargetDelayMillis
    private var previousArrivalMillis: Long? = null
    private var previousTimestampNanos: Long? = null
    private var previousSequence: Long? = null
    private var estimatedJitterMillis = 0.0
    private var lowerTargetSinceMillis: Long? = null
    private var accepted = 0L
    private var rejected = 0L
    private var concealed = 0L
    private var refills = 0L
    private var consecutiveMissing = 0

    init {
        require(initialTargetDelayMillis in 20..maximumDelayMillis)
        require(maximumDelayMillis in initialTargetDelayMillis..500)
        require(frameDurationMillis in RelayMediaPacket.SUPPORTED_FRAME_DURATIONS)
        require(adaptiveMode in 0..2)
        require(initialTargetDelayMillis % frameDurationMillis == 0)
        require(maximumDelayMillis % frameDurationMillis == 0)
    }

    @Synchronized
    fun offer(packet: RelayMediaPacket, arrivalMillis: Long): Boolean {
        if (arrivalMillis < 0) return false
        val expected = expectedSequence
        if ((expected != null && isBefore(packet.sequence, expected)) || frames.containsKey(packet.sequence)) {
            rejected++
            return false
        }

        require(packet.durationMillis == frameDurationMillis)
        val capacity = maximumDelayMillis / frameDurationMillis + 2
        if (expected != null && forwardDistance(expected, packet.sequence) >= capacity) {
            // A blocked audio sink or a long transport gap can leave the consumer
            // behind forever: rejecting every new packet cannot close that gap.
            // Discard stale queued audio and refill from current media instead.
            resetTimeline()
            refills++
        }

        if (expectedSequence == null) {
            expectedSequence = packet.sequence
            firstSequence = packet.sequence
            playoutStartsAtMillis = arrivalMillis + currentTargetDelayMillis
        }
        val priorArrival = previousArrivalMillis
        val priorTimestamp = previousTimestampNanos
        val priorSequence = previousSequence
        if (priorArrival != null && priorTimestamp != null && priorSequence != null &&
            !isBefore(packet.sequence, priorSequence)
        ) {
            val arrivalDelta = arrivalMillis - priorArrival
            val mediaDelta = (packet.timestampNanos - priorTimestamp) / 1_000_000L
            if (arrivalDelta >= 0 && mediaDelta > 0) {
                val transitVariation = kotlin.math.abs(arrivalDelta - mediaDelta).toDouble()
                estimatedJitterMillis += (transitVariation - estimatedJitterMillis) / 16.0
                // Quantize upward to whole frames. Comparing a fractional target
                // with a 20 ms step used to oscillate the deadline on every packet.
                // Increases protect active playback; reductions use the selected,
                // paced adaptive policy below.
                val wanted = (kotlin.math.ceil(
                    (initialTargetDelayMillis + estimatedJitterMillis * 4.0) / frameDurationMillis,
                ).toInt() * frameDurationMillis).coerceAtMost(maximumDelayMillis)
                val adjustment = if (wanted > currentTargetDelayMillis) frameDurationMillis else 0
                if (adjustment != 0) {
                    lowerTargetSinceMillis = null
                    currentTargetDelayMillis = (currentTargetDelayMillis + adjustment).coerceIn(initialTargetDelayMillis, maximumDelayMillis)
                    playoutStartsAtMillis = (playoutStartsAtMillis ?: arrivalMillis) + adjustment
                } else if (adaptiveMode > 0 && wanted < currentTargetDelayMillis && estimatedJitterMillis < 12.0) {
                    val interval = if (adaptiveMode == 1) 5_000L else 1_500L
                    // Require sustained headroom: a brief calm interval must not
                    // pull the deadline forward immediately after a burst.
                    val stableSince = lowerTargetSinceMillis ?: arrivalMillis.also { lowerTargetSinceMillis = it }
                    if (arrivalMillis - stableSince >= interval) {
                        currentTargetDelayMillis = (currentTargetDelayMillis - frameDurationMillis).coerceAtLeast(initialTargetDelayMillis)
                        // A frame is removed from the live timeline only at this paced interval.
                        playoutStartsAtMillis = (playoutStartsAtMillis ?: arrivalMillis) - frameDurationMillis
                        lowerTargetSinceMillis = arrivalMillis
                    }
                } else {
                    lowerTargetSinceMillis = null
                }
            }
        }
        if (priorSequence == null || !isBefore(packet.sequence, priorSequence)) {
            previousArrivalMillis = arrivalMillis
            previousTimestampNanos = packet.timestampNanos
            previousSequence = packet.sequence
        }
        if (frames.size >= capacity) {
            rejected++
            return false
        }
        frames[packet.sequence] = packet
        accepted++
        return true
    }

    @Synchronized
    fun poll(nowMillis: Long): Output? {
        val expected = expectedSequence ?: return null
        val first = firstSequence ?: return null
        val playoutStart = playoutStartsAtMillis ?: return null
        val sequenceOffset = forwardDistance(first, expected)
        val frameDeadline = playoutStart + sequenceOffset * frameDurationMillis
        if (nowMillis < frameDeadline) return null

        val packet = frames.remove(expected)
        expectedSequence = (expected + 1) and 0xffff_ffffL
        if (packet != null) {
            lastTimestampNanos = packet.timestampNanos
            consecutiveMissing = 0
            return Output.Frame(packet)
        }
        concealed++
        consecutiveMissing++
        lastTimestampNanos += frameDurationMillis * 1_000_000L
        val missing = Output.Missing(expected, lastTimestampNanos)
        if (consecutiveMissing >= 5 && frames.isEmpty()) {
            // Stop synthesizing metallic PLC indefinitely after a real outage.
            resetTimeline()
            refills++
        }
        return missing
    }

    @Synchronized
    fun clear() {
        resetTimeline()
        currentTargetDelayMillis = initialTargetDelayMillis
        lowerTargetSinceMillis = null
        estimatedJitterMillis = 0.0
        accepted = 0L
        rejected = 0L
        concealed = 0L
        refills = 0L
    }

    @Synchronized
    fun statistics(): Statistics = Statistics(accepted, rejected, concealed, refills, frames.size, currentTargetDelayMillis)

    private fun resetTimeline() {
        lowerTargetSinceMillis = null
        frames.clear()
        expectedSequence = null
        firstSequence = null
        playoutStartsAtMillis = null
        lastTimestampNanos = 0L
        previousArrivalMillis = null
        previousTimestampNanos = null
        previousSequence = null
        consecutiveMissing = 0
    }

    private fun isBefore(sequence: Long, expected: Long): Boolean =
        ((sequence - expected) and 0xffff_ffffL) > 0x7fff_ffffL

    private fun forwardDistance(from: Long, to: Long): Long = (to - from) and 0xffff_ffffL
}
