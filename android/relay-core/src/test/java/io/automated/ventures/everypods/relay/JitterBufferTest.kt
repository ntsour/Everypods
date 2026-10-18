package io.automated.ventures.everypods.relay

import io.automated.ventures.everypods.relay.JitterBuffer.Pull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private fun p(seq: Long) = byteArrayOf(seq.toByte())

    private fun JitterBuffer.feed(range: LongRange, t0: Double = 0.0) = range.forEach { offer(it, it * 960, p(it), t0 + it * 20.0) }

    @Test fun buffersUntilStartTargetThenPlaysInOrder() {
        val jb = JitterBuffer()
        jb.feed(0L..8L) // 180 ms
        assertEquals(Pull.Buffering, jb.pull())
        jb.feed(9L..9L) // 200 ms
        val first = jb.pull()
        assertTrue(first is Pull.Frame && first.seq == 0L)
        assertEquals(1L, (jb.pull() as Pull.Frame).seq)
        assertEquals(200.0, jb.targetMs, 0.2)
    }

    @Test fun reorderedPacketsPlayInSequence() {
        val jb = JitterBuffer()
        listOf(1L, 0L, 3L, 2L, 5L, 4L, 7L, 6L, 9L, 8L).forEach { jb.offer(it, it, p(it), it * 20.0) }
        val seqs = (0 until 10).map { (jb.pull() as Pull.Frame).seq }
        assertEquals((0L..9L).toList(), seqs)
    }

    @Test fun gapIsLostWithFecFromNextPacket() {
        val jb = JitterBuffer()
        jb.feed(0L..4L); jb.feed(6L..12L)
        repeat(5) { jb.pull() }
        val lost = jb.pull()
        assertTrue(lost is Pull.Lost && lost.seq == 5L && lost.fecFrom!!.contentEquals(p(6)))
        assertEquals(6L, (jb.pull() as Pull.Frame).seq)
        assertEquals(1L, jb.lost)
    }

    @Test fun latePacketDroppedAndCounted() {
        val jb = JitterBuffer()
        jb.feed(0L..10L)
        repeat(3) { jb.pull() }
        jb.offer(1, 1, p(1), 500.0)
        jb.offer(5, 5, p(5), 500.0) // duplicate
        assertEquals(1L, jb.late)
        assertEquals(1L, jb.duplicates)
    }

    @Test fun underrunRebuffersAndRaisesTarget() {
        val jb = JitterBuffer()
        jb.feed(0L..9L)
        repeat(10) { assertTrue(jb.pull() is Pull.Frame) }
        assertEquals(Pull.Buffering, jb.pull())
        assertEquals(1L, jb.underruns)
        assertTrue(jb.targetMs >= 239.0)
        jb.feed(10L..20L, 0.0)
        assertEquals(Pull.Buffering, jb.pull()) // 220 ms < ~240 ms target
        jb.feed(21L..23L, 0.0)
        assertEquals(10L, (jb.pull() as Pull.Frame).seq)
    }

    @Test fun targetStaysWithin100To500() {
        val jb = JitterBuffer()
        val rnd = java.util.Random(1)
        // Huge jitter pushes the target to the cap.
        for (s in 0L until 400L) jb.offer(s, s, p(s), s * 20.0 + rnd.nextInt(600))
        repeat(200) { jb.pull() }
        assertTrue(jb.targetMs <= 500.0)
        // Perfectly regular arrivals let it decay, but never below 100.
        val calm = JitterBuffer()
        for (s in 0L until 20_000L) { calm.offer(s, s, p(s), s * 20.0); calm.pull() }
        assertTrue("target ${calm.targetMs}", calm.targetMs in 100.0..101.0)
    }

    @Test fun burstBeyondMaxIsTrimmed() {
        val jb = JitterBuffer()
        jb.feed(0L..9L); jb.pull()
        jb.feed(10L..50L) // ~800 ms queued
        jb.pull()
        assertTrue(jb.depthMs() <= 600)
        assertTrue(jb.trimmed > 0)
    }

    @Test fun flushDropsQueuedAudioAndSenderRestartResets() {
        val jb = JitterBuffer()
        jb.feed(0L..20L); jb.pull()
        jb.flush()
        assertEquals(0, jb.depthFrames())
        assertEquals(Pull.Buffering, jb.pull())
        jb.feed(0L..20L); jb.pull()
        jb.offer(100_000, 0, p(0), 0.0) // far jump = sender restart
        assertEquals(1, jb.depthFrames())
    }
}
