package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class MotionPeakTrackerTest {
    private fun wave(step: Int): List<Double> = listOf(0, 300, -300, 300, -300, 0)
        .zipWithNext().flatMap { (from, to) ->
            val count = kotlin.math.abs(to - from) / step
            (1..count).map { from + (to - from) * it.toDouble() / count }
        }.let { listOf(0.0) + it }

    @Test fun `gentle repeated nods retain their extrema when every sample step is small`() {
        val tracker = MotionPeakTracker(50.0)
        val points = wave(10).mapNotNull(tracker::add)
        assertEquals(listOf(300.0, -300.0, 300.0, -300.0), points.map { it.value })
        assertEquals(listOf(true, false, true, false), points.map { it.isPeak })
    }

    @Test fun `changing the sample rate does not change the recognized reversals`() {
        val slow = MotionPeakTracker(50.0)
        val fast = MotionPeakTracker(50.0)
        assertEquals(wave(50).mapNotNull(slow::add).map { it.value },
            wave(5).mapNotNull(fast::add).map { it.value })
    }

    @Test fun `small sensor jitter cannot accumulate into a reversal`() {
        val tracker = MotionPeakTracker(50.0)
        val points = List(300) { if (it % 2 == 0) 15.0 else -15.0 }.mapNotNull(tracker::add)
        assertTrue(points.isEmpty())
    }

    @Test fun `one excursion produces only one turning point rather than a repeated gesture`() {
        val tracker = MotionPeakTracker(50.0)
        val values = (0..300 step 10).map(Int::toDouble) + (290 downTo 0 step 10).map(Int::toDouble)
        assertEquals(listOf(300.0), values.mapNotNull(tracker::add).map { it.value })
    }

    @Test fun `peak order stays monotonic after the rolling sensor buffer would be full`() {
        val tracker = MotionPeakTracker(50.0)
        repeat(150) { tracker.add(0.0) }
        val points = wave(10).mapNotNull(tracker::add)
        assertTrue(points.all { it.index > 100 })
        assertTrue(points.zipWithNext().all { (first, second) -> first.index < second.index })
        tracker.reset()
        assertNull(tracker.add(0.0))
    }
}
