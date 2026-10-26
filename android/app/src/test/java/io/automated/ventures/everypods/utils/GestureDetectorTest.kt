package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.*
import org.junit.Assert.*
import org.junit.Test

class GestureDetectorTest {
    private var now = 0L
    private val detector = GestureDetector { now }
    private val tuning = GestureTuning()
    private fun sample(v: Int, h: Int = 0, dt: Long = 100, t: GestureTuning = tuning): HeadGesture? {
        now += dt
        return detector.process(h, v, t, t)
    }
    private fun repetition(vertical: Boolean = true, amplitude: Int = 800): HeadGesture? {
        if (vertical) { sample(amplitude); sample(-amplitude); return sample(0) }
        sample(0, amplitude); sample(0, -amplitude); return sample(0)
    }
    @Test fun `requires exact count and amplitude and neutral return`() {
        assertNull(repetition(amplitude = 799))
        assertNull(repetition())
        assertNull(sample(800))
        assertNull(sample(-800))
        assertEquals(HeadGesture.NOD, sample(200))
    }
    @Test fun `horizontal movement maps to shake`() {
        assertNull(repetition(false))
        assertEquals(HeadGesture.SHAKE, repetition(false))
    }
    @Test fun `single spikes and one sided movements do not qualify`() {
        repeat(8) { sample(1500); assertNull(sample(0)) }
        assertEquals(0, detector.status.nod.repetitions)
    }
    @Test fun `diagonal motion is ambiguous`() {
        repeat(4) { assertNull(sample(900, 900)); assertNull(sample(-900, -900)); assertNull(sample(0)) }
    }
    @Test fun `speed cannot reduce count`() {
        val t = tuning.copy(repetitions = 4)
        repeat(3) { assertNull(sample(900, dt = 20, t = t)); assertNull(sample(-900, dt = 20, t = t)); assertNull(sample(0, dt = 20, t = t)) }
        sample(900, dt = 20, t = t); sample(-900, dt = 20, t = t)
        assertEquals(HeadGesture.NOD, sample(0, dt = 20, t = t))
    }
    @Test fun `cooldown requires neutral and cannot be bypassed by outliers`() {
        repetition(); assertEquals(HeadGesture.NOD, repetition())
        repeat(8) { assertNull(sample(800)); assertNull(sample(-800)); assertNull(sample(0)) }
        // A completed cooldown alone is insufficient; a stable neutral interval is required.
        assertNull(repetition())
        sample(Int.MAX_VALUE)
        assertNull(repetition())
        sample(0); sample(0, dt = 300)
        assertNull(repetition()); assertEquals(HeadGesture.NOD, repetition())
    }
    @Test fun `stream gaps discard old repetitions`() {
        repetition(); sample(0, dt = 1001)
        assertNull(repetition()); assertEquals(HeadGesture.NOD, repetition())
    }
    @Test fun `incomplete sequence expires without qualifying excursions`() {
        sample(800); repeat(16) { sample(0) }
        sample(-800); assertNull(sample(0))
        assertEquals(0, detector.status.nod.repetitions)
    }
    @Test fun `sequence cannot accumulate beyond six seconds`() {
        val t = tuning.copy(repetitions = 4)
        repeat(3) { sample(800, dt = 700, t = t); sample(-800, dt = 700, t = t); assertNull(sample(0, dt = 700, t = t)) }
        assertNull(sample(800, dt = 700, t = t)); assertNull(sample(-800, dt = 700, t = t)); assertNull(sample(0, dt = 700, t = t))
    }
    @Test fun `reset removes partial progress`() {
        repetition(); detector.reset(); assertNull(repetition())
    }
    @Test fun `cross axis motion below its own threshold still rejects diagonals`() {
        val highShake = GestureTuning(amplitude = 2000)
        repeat(2) {
            now += 100; assertNull(detector.process(600, 800, tuning, highShake))
            now += 100; assertNull(detector.process(-600, -800, tuning, highShake))
            now += 100; assertNull(detector.process(0, 0, tuning, highShake))
        }
    }
    @Test fun `axis isolation accepts exact one point five ratio`() {
        val highShake = GestureTuning(amplitude = 2000)
        repeat(2) { index ->
            now += 100; assertNull(detector.process(600, 900, tuning, highShake))
            now += 100; assertNull(detector.process(-600, -900, tuning, highShake))
            now += 100
            assertEquals(if (index == 1) HeadGesture.NOD else null, detector.process(0, 0, tuning, highShake))
        }
    }

    @Test fun `speech ending discards partial motion while preserving action cooldown`() {
        repetition(false); assertEquals(HeadGesture.SHAKE, repetition(false))
        detector.invalidate() // Speech is stopped by that shake; normal controls become active.
        assertNull(repetition(false))
        assertNull(repetition(false))
        // Stop moving and allow both cooldown and stable-neutral rearming.
        sample(0, dt = 1000); sample(0, dt = 500)
        assertNull(repetition(false))
        assertEquals(HeadGesture.SHAKE, repetition(false))
    }

}
