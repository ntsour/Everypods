package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class RepeatedMotionPatternTest {
    private fun pattern(gap: Int, count: Int = 4) = List(count) {
        it * gap to if (it % 2 == 0) 400.0 else -400.0
    }

    @Test fun `two steady repetitions are recognized regardless of packet delivery timing`() {
        assertTrue(RepeatedMotionPattern.accepts(pattern(8)))
    }

    @Test fun `one complete repetition is insufficient`() {
        assertFalse(RepeatedMotionPattern.accepts(pattern(8, 2)))
    }

    @Test fun `rapid bounces cannot masquerade as repeated deliberate movement`() {
        assertFalse(RepeatedMotionPattern.accepts(pattern(1)))
    }

    @Test fun `two repetitions must span at least eight tenths of a second`() {
        assertFalse(RepeatedMotionPattern.accepts(pattern(5)))
        assertTrue(RepeatedMotionPattern.accepts(pattern(7)))
    }

    @Test fun `unrelated movements spaced too far apart do not combine into a gesture`() {
        assertFalse(RepeatedMotionPattern.accepts(pattern(31)))
    }

    @Test fun `successive peaks on the same side do not count as full repetitions`() {
        val points = pattern(8).toMutableList().apply { this[3] = 24 to 400.0 }
        assertFalse(RepeatedMotionPattern.accepts(points))
    }
}
