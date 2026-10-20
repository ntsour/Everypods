package io.automated.ventures.everypods.relay.transport

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
    fun adaptiveReductionLowersTargetOnlyInTheSelectedMode() {
        val disabled = RelayJitterBuffer(200, 300, 20, adaptiveMode = 0)
        val adaptive = RelayJitterBuffer(200, 300, 20, adaptiveMode = 1)
        for (sequence in 0L..700L) {
            val arrival = sequence * 20L + if (sequence % 2L == 0L) 0L else 40L
            val item = packet(sequence)
            disabled.offer(item, arrival)
            adaptive.offer(item, arrival)
            disabled.poll(arrival + 600L)
            adaptive.poll(arrival + 600L)
        }
        assertTrue("Jitter should first grow the target", adaptive.statistics().targetDelayMillis > 200)
        val grown = adaptive.statistics().targetDelayMillis
        for (sequence in 701L..1_400L) {
            val item = packet(sequence)
            val arrival = sequence * 20L
            disabled.offer(item, arrival)
            adaptive.offer(item, arrival)
            disabled.poll(arrival + 600L)
            adaptive.poll(arrival + 600L)
        }
        assertEquals(grown, disabled.statistics().targetDelayMillis)
        assertTrue(adaptive.statistics().targetDelayMillis < grown)
        assertTrue(adaptive.statistics().targetDelayMillis >= 200)
    }
}
