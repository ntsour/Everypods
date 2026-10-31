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

    @Test fun `stricter direction rejects diagonals accepted by the default on both axes`() {
        for (gesture in HeadGesture.entries) for (ratio in listOf(150, 300)) {
            var time = 0L
            val detector = GestureDetector { time }
            val strict = tuning.copy(axisDominancePercent = ratio)
            val events = listOf(900, -900, 0, 900, -900, 0).mapNotNull { value ->
                time += 100
                val offAxis = value / 2
                detector.process(if (gesture == HeadGesture.SHAKE) value else offAxis,
                    if (gesture == HeadGesture.NOD) value else offAxis, strict, strict)
            }
            assertEquals(if (ratio == 150) listOf(gesture) else emptyList<HeadGesture>(), events)
        }
    }

    @Test fun `strict direction accepts its exact boundary and rejects just beyond it`() {
        for (gesture in HeadGesture.entries) for (offAxisPeak in listOf(300, 301)) {
            var time = 0L
            val detector = GestureDetector { time }
            val strict = tuning.copy(axisDominancePercent = 300)
            val events = listOf(1, -1, 0, 1, -1, 0).mapNotNull { sign ->
                time += 100
                detector.process(if (gesture == HeadGesture.SHAKE) sign * 900 else sign * offAxisPeak,
                    if (gesture == HeadGesture.NOD) sign * 900 else sign * offAxisPeak, strict, strict)
            }
            assertEquals(if (offAxisPeak == 300) listOf(gesture) else emptyList<HeadGesture>(), events)
        }
    }

    @Test fun `shake uses its own direction strictness when nod is stricter`() {
        val strictNod = tuning.copy(axisDominancePercent = 400)
        val events = listOf(900, -900, 0, 900, -900, 0).mapNotNull { value ->
            now += 100
            detector.process(value, value / 2, strictNod, tuning)
        }
        assertEquals(listOf(HeadGesture.SHAKE), events)
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

    @Test fun `ear or context change requires neutral and discards partial repetitions`() {
        repetition()
        detector.awaitNeutral()
        assertNull(repetition()) // Insertion/removal motion cannot complete the old command.
        sample(0); sample(0, dt = 300)
        assertNull(repetition())
        assertEquals(HeadGesture.NOD, repetition())
    }
    @Test fun `context change preserves completed gesture cooldown`() {
        repetition(false); assertEquals(HeadGesture.SHAKE, repetition(false))
        detector.awaitNeutral()
        repeat(2) { assertNull(repetition(false)) }
        sample(0, dt = 1000); sample(0, dt = 500)
        assertNull(repetition(false))
        assertEquals(HeadGesture.SHAKE, repetition(false))
    }

    @Test fun `neutral crossed between sensor samples still completes exactly two repetitions`() {
        // At 40 ms cadence a zero crossing can fall between the opposite peak and next peak.
        assertNull(sample(900, dt = 40))
        assertNull(sample(-900, dt = 40))
        assertNull(sample(900, dt = 40))
        assertNull(sample(-900, dt = 40))
        assertEquals(HeadGesture.NOD, sample(0, dt = 40))
    }

    @Test fun `short phase limit accepts fast gestures and rejects slow ones on both axes`() {
        for (gesture in HeadGesture.entries) for (stepMs in listOf(100L, 201L)) {
            var time = 0L
            val detector = GestureDetector { time }
            val fast = tuning.copy(maxPhaseGapMs = 200)
            val events = listOf(900, -900, 0, 900, -900, 0).mapNotNull { value ->
                time += stepMs
                detector.process(if (gesture == HeadGesture.SHAKE) value else 0,
                    if (gesture == HeadGesture.NOD) value else 0, fast, fast)
            }
            assertEquals(if (stepMs == 100L) listOf(gesture) else emptyList<HeadGesture>(), events)
        }
    }

    @Test fun `total duration accepts its boundary and rejects slower sequences with valid phase gaps`() {
        for (stepMs in listOf(100L, 120L)) {
            var time = 0L
            val detector = GestureDetector { time }
            val fast = tuning.copy(maxDurationMs = 500)
            val events = listOf(900, -900, 0, 900, -900, 0).mapNotNull { value ->
                time += stepMs
                detector.process(0, value, fast, fast)
            }
            assertEquals(if (stepMs == 100L) listOf(HeadGesture.NOD) else emptyList<HeadGesture>(), events)
        }
    }

    @Test fun `nod and shake use their own timing limits`() {
        val fast = tuning.copy(maxPhaseGapMs = 200)
        val events = listOf(900, -900, 0, 900, -900, 0).mapNotNull { value ->
            now += 300
            detector.process(value, 0, fast, tuning)
        }
        assertEquals(listOf(HeadGesture.SHAKE), events)
    }
    @Test fun `crossing the neutral band cannot count one oscillation twice`() {
        assertNull(sample(900, dt = 40))
        assertNull(sample(-900, dt = 40))
        assertNull(sample(900, dt = 40))
        assertNull(sample(0, dt = 40))
        assertEquals(1, detector.status.nod.repetitions)
    }

}
