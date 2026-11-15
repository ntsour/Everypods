package io.automated.ventures.everypods.relay.transport

import io.automated.ventures.everypods.relay.RelayPcmCatchUp
import org.junit.Assert.*
import org.junit.Test

class RelayJitterBufferTest {
    private fun packet(sequence: Long, timestampMillis: Long = sequence * 20) = RelayMediaPacket(
        sequence, timestampMillis * 1_000_000, 20, byteArrayOf(1),
    )

    @Test
    fun smallArrivalVariationDoesNotOscillateTheActiveDeadline() {
        val buffer = RelayJitterBuffer()
        var previousTarget = 200
        repeat(20) { sequence ->
            buffer.offer(packet(sequence.toLong()), sequence * 20L + sequence % 2)
            val target = buffer.statistics().targetDelayMillis
            assertTrue("Active playback must not be pulled backward", target >= previousTarget)
            assertEquals(0, target % 20)
            assertTrue(target <= 500)
            previousTarget = target
        }
    }

    @Test
    fun batchedCaptureDeliversEveryFrameWithoutFabricatingPacketLoss() {
        val buffer = RelayJitterBuffer()
        val totalFrames = 400
        var nextSequence = 0L
        var played = 0
        for (time in 0L..9_000L) {
            if (time % 80L == 0L && nextSequence < totalFrames) {
                repeat(4) {
                    assertTrue(buffer.offer(packet(nextSequence++), time))
                }
            }
            if (time % 20L == 0L && played < totalFrames) {
                when (val output = buffer.poll(time)) {
                    is RelayJitterBuffer.Output.Frame -> {
                        assertEquals(played.toLong(), output.packet.sequence)
                        played++
                    }
                    is RelayJitterBuffer.Output.Missing -> fail("No network packets were lost: $output")
                    null -> Unit
                }
            }
        }
        assertEquals(totalFrames, played)
        assertEquals(0L, buffer.statistics().concealed)
        assertEquals(0L, buffer.statistics().rejected)
    }

    @Test
    fun blockedSinkRefillsFromFreshMediaInsteadOfRejectingNewPacketsForever() {
        val buffer = RelayJitterBuffer()
        // Consumer is blocked for several seconds while capture keeps running.
        repeat(121) { buffer.offer(packet(it.toLong()), it * 20L) }
        assertTrue(buffer.statistics().refills > 0)
        assertTrue(buffer.statistics().queued <= 27)
        var realFrames = 0
        repeat(100) { index ->
            val sequence = 121L + index
            val time = sequence * 20
            assertTrue(buffer.offer(packet(sequence), time))
            if (buffer.poll(time) is RelayJitterBuffer.Output.Frame) realFrames++
        }
        assertTrue("Playback should recover after the sink unblocks", realFrames >= 90)
    }

    @Test
    fun reorderedPacketsArePlayedInSequence() {
        val buffer = RelayJitterBuffer()
        buffer.offer(packet(0), 0)
        buffer.offer(packet(2), 20)
        buffer.offer(packet(1), 30)
        val playoutStart = buffer.statistics().targetDelayMillis.toLong()
        repeat(3) { index ->
            val output = buffer.poll(playoutStart + index * 20) as RelayJitterBuffer.Output.Frame
            assertEquals(index.toLong(), output.packet.sequence)
        }
    }

    @Test
    fun oneMissingFrameIsConcealedWithoutDiscardingTheFollowingFrame() {
        val buffer = RelayJitterBuffer()
        buffer.offer(packet(0), 0)
        buffer.offer(packet(2), 40)
        assertTrue(buffer.poll(200) is RelayJitterBuffer.Output.Frame)
        assertTrue(buffer.poll(220) is RelayJitterBuffer.Output.Missing)
        assertEquals(2L, (buffer.poll(240) as RelayJitterBuffer.Output.Frame).packet.sequence)
        assertEquals(1L, buffer.statistics().concealed)
        assertEquals(0L, buffer.statistics().refills)
    }

    @Test
    fun longOutageWaitsForRefillInsteadOfSynthesizingLossIndefinitely() {
        val buffer = RelayJitterBuffer()
        buffer.offer(packet(0), 0)
        assertTrue(buffer.poll(200) is RelayJitterBuffer.Output.Frame)
        repeat(5) { assertTrue(buffer.poll(220L + it * 20) is RelayJitterBuffer.Output.Missing) }
        assertNull(buffer.poll(320))
        buffer.offer(packet(100), 1_000)
        assertNull(buffer.poll(1_199))
        assertEquals(100L, (buffer.poll(1_200) as RelayJitterBuffer.Output.Frame).packet.sequence)
    }

    @Test
    fun sequenceWrapKeepsPlaybackContinuous() {
        val buffer = RelayJitterBuffer()
        val sequences = listOf(0xffff_fffeL, 0xffff_ffffL, 0L)
        sequences.forEachIndexed { index, sequence -> buffer.offer(packet(sequence, index * 20L), index * 20L) }
        sequences.forEachIndexed { index, sequence ->
            assertEquals(sequence, (buffer.poll(200L + index * 20) as RelayJitterBuffer.Output.Frame).packet.sequence)
        }
    }

    @Test
    fun adaptiveReductionRemovesActualDelayThroughARealtimeSink() {
        for (duration in listOf(10, 20)) for (mode in listOf(1, 2)) {
            val result = simulateSink(duration, mode)
            assertTrue(result.grownTarget > 80)
            assertEquals(80, result.statistics.targetDelayMillis)
            val reduction = result.grownTarget - result.statistics.targetDelayMillis
            assertEquals("Changing the target must remove audible backlog", reduction.toLong(), result.grownAge - result.finalAge)
            assertEquals(reduction.toLong(), result.statistics.caughtUpFrames * duration)
            assertEquals(0L, result.statistics.concealed)
            assertEquals(0L, result.statistics.rejected)
            assertEquals(0L, result.statistics.refills)
            val interval = if (mode == 1) 5_000L else 1_500L
            result.catchUpTimes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= interval) }
        }
    }

    @Test
    fun disabledReductionKeepsEveryFrameAndTheGrownDelay() {
        val result = simulateSink(10, 0)
        assertEquals(result.grownTarget, result.statistics.targetDelayMillis)
        assertEquals(result.grownAge, result.finalAge)
        assertEquals(0L, result.statistics.caughtUpFrames)
    }

    @Test
    fun catchUpPreservesSequenceOrderAcrossWrap() {
        val result = simulateSink(10, 2, sequenceBase = 0xffff_fff0L)
        assertTrue(result.statistics.caughtUpFrames > 0)
        assertEquals(0L, result.statistics.concealed)
        assertEquals(0L, result.statistics.refills)
    }

    @Test
    fun packetLossIsConcealedWithoutCrossfadingAcrossAMissingFrame() {
        val result = simulateSink(10, 2, lostIndex = 450)
        assertEquals(1L, result.statistics.concealed)
        assertEquals(0L, result.statistics.refills)
        assertTrue(result.statistics.caughtUpFrames > 0)
        assertEquals(80, result.statistics.targetDelayMillis)
    }

    @Test
    fun clearingForResumeStartsAFreshTimelineWithoutCatchUp() {
        val buffer = RelayJitterBuffer(80, 160, 10, 2)
        buffer.offer(RelayMediaPacket(0, 0, 10, byteArrayOf(1)), 0)
        buffer.offer(RelayMediaPacket(1, 10_000_000, 10, byteArrayOf(1)), 30)
        assertTrue(buffer.statistics().targetDelayMillis > 80)
        buffer.clear()
        buffer.offer(RelayMediaPacket(100, 1_000_000_000, 10, byteArrayOf(1)), 1_000)
        assertNull(buffer.poll(1_079))
        val output = buffer.poll(1_080) as RelayJitterBuffer.Output.Frame
        assertEquals(100L, output.packet.sequence)
        assertNull(output.catchUpFrom)
        assertEquals(80, buffer.statistics().targetDelayMillis)
        assertEquals(0L, buffer.statistics().caughtUpFrames)
    }

    @Test
    fun lowerTargetsWaitForTheirConfiguredDeadline() {
        for (target in listOf(20, 30, 40)) {
            val buffer = RelayJitterBuffer(target, target, 10)
            buffer.offer(RelayMediaPacket(0, 0, 10, byteArrayOf(1)), 100)
            assertNull(buffer.poll(100L + target - 1))
            assertTrue(buffer.poll(100L + target) is RelayJitterBuffer.Output.Frame)
        }
    }

    @Test
    fun briefCalmPeriodDoesNotImmediatelyReduceAGrownBuffer() {
        val result = simulateSink(10, 2)
        assertTrue("One second of calm must not trigger a reduction", result.catchUpTimes.first() >= 3_500L)
    }

    private data class Simulation(
        val grownTarget: Int,
        val grownAge: Long,
        val finalAge: Long,
        val statistics: RelayJitterBuffer.Statistics,
        val catchUpTimes: List<Long>,
    )

    /** Model the production write loop, including a pending frame when the sink is full. */
    private fun simulateSink(duration: Int, mode: Int, sequenceBase: Long = 0, lostIndex: Long? = null): Simulation {
        val buffer = RelayJitterBuffer(80, 160, duration, mode)
        var sinkEndsAt = 0L
        var pending: RelayJitterBuffer.Output? = null
        var previousSequence: Long? = null
        var grownTarget = 0
        var grownAge = 0L
        var finalAge = 0L
        val catchUps = mutableListOf<Long>()
        fun offer(index: Long, time: Long) {
            if (index == lostIndex) return
            assertTrue(buffer.offer(RelayMediaPacket((sequenceBase + index) and 0xffff_ffffL,
                index * duration * 1_000_000L, duration, byteArrayOf(1)), time))
        }
        fun consume(packet: RelayMediaPacket) {
            previousSequence?.let { assertEquals((it + 1) and 0xffff_ffffL, packet.sequence) }
            previousSequence = packet.sequence
        }
        for (time in 0L..40_000L) {
            // Real captured timestamps: two-frame delivery batches, then a perfect cadence.
            if (time in 1L..2_000L && time % (duration * 2) == 0L) {
                repeat(2) { offer(time / duration - 2 + it, time) }
            } else if (time > 2_000L && time % duration == 0L) offer(time / duration - 1, time)
            while (true) {
                val output = pending ?: buffer.poll(time) ?: break
                pending = output
                if (sinkEndsAt - time + duration > 40) break
                if (output is RelayJitterBuffer.Output.Missing) {
                    assertNotNull("Only the deliberately lost packet may be concealed", lostIndex)
                    assertEquals((sequenceBase + checkNotNull(lostIndex)) and 0xffff_ffffL, output.sequence)
                    assertEquals((checkNotNull(previousSequence) + 1) and 0xffff_ffffL, output.sequence)
                    previousSequence = output.sequence
                    sinkEndsAt = maxOf(time, sinkEndsAt) + duration
                    pending = null
                    continue
                }
                assertTrue("No packets were lost", output is RelayJitterBuffer.Output.Frame)
                output as RelayJitterBuffer.Output.Frame
                output.catchUpFrom?.let { consume(it); catchUps += time }
                consume(output.packet)
                // The production crossfade must write one frame, not two, to this fixed-rate sink.
                val pcm = RelayPcmCatchUp.decode(output, 2) { ShortArray(duration * 2) }
                val start = maxOf(time, sinkEndsAt)
                sinkEndsAt = start + pcm.size / 2
                val age = start - output.packet.timestampNanos / 1_000_000L
                if (time in 1_800L..1_999L) {
                    grownTarget = buffer.statistics().targetDelayMillis
                    grownAge = age
                }
                if (time > 39_000) finalAge = age
                pending = null
            }
        }
        return Simulation(grownTarget, grownAge, finalAge, buffer.statistics(), catchUps)
    }
}
