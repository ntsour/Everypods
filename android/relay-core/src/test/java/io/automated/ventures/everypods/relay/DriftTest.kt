package io.automated.ventures.everypods.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTest {
    @Test fun ratioFollowsDepthErrorAndIsClamped() {
        val d = DriftCorrector()
        assertEquals(1.0, d.update(205.0, 200.0), 0.0) // inside deadband
        val deep = DriftCorrector(); repeat(2000) { deep.update(300.0, 200.0) }
        assertTrue(deep.ratio > 1.0005)
        val shallow = DriftCorrector(); repeat(2000) { shallow.update(120.0, 200.0) }
        assertTrue(shallow.ratio < 0.9995)
        val huge = DriftCorrector(); repeat(2000) { huge.update(5000.0, 200.0) }
        assertEquals(1.002, huge.ratio, 1e-9)
    }

    @Test fun simulatedClockSkewIsAbsorbed() {
        // Sender runs 300 ppm fast: without correction the buffer grows by ~6 ms per 1000 frames.
        val jb = JitterBuffer()
        val drift = DriftCorrector()
        var senderPos = 0.0
        var seq = 0L
        var consumed = 0.0
        var outFrames = 0L
        for (tick in 0 until 90_000) { // 30 minutes of 20 ms output frames
            senderPos += 1.0003
            while (senderPos >= 1.0) { jb.offer(seq, seq, byteArrayOf(0), tick * 20.0); seq++; senderPos -= 1.0 }
            val ratio = drift.update(jb.depthMs().toDouble(), jb.targetMs)
            consumed += ratio
            while (consumed >= 1.0) { jb.pull(); consumed -= 1.0 }
            outFrames++
        }
        assertTrue("depth ${jb.depthMs()}", jb.depthMs() in 60..320)
        assertTrue(drift.ratio in 1.0..1.002)
        assertEquals(0L, jb.trimmed)
    }

    @Test fun resamplerIdentityAndLength() {
        val r = LinearResampler(2)
        val input = ShortArray(960 * 2) { (it / 2).toShort() }
        assertTrue(r.process(input, 1.0).contentEquals(input))
        val r2 = LinearResampler(2)
        var outFrames = 0
        repeat(1000) { outFrames += r2.process(input, 1.001).size / 2 }
        assertEquals(960_000 / 1.001, outFrames.toDouble(), 3.0)
        val r3 = LinearResampler(2)
        var slow = 0
        repeat(1000) { slow += r3.process(input, 0.999).size / 2 }
        assertEquals(960_000 / 0.999, slow.toDouble(), 3.0)
    }

    @Test fun resamplerInterpolatesSmoothly() {
        val r = LinearResampler(1)
        val ramp = ShortArray(100) { (it * 100).toShort() }
        val out = r.process(ramp, 0.5)
        assertEquals(50.toShort(), out[1])
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1])
    }
}
