package io.automated.ventures.everypods.bluetooth.hrm.dircon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirconRetryPolicyTest {
    @Test fun `retry sequence is bounded and settles at one minute`() {
        assertEquals(1_000L, DirconRetryPolicy.delayMs(1, 1.0))
        assertEquals(2_000L, DirconRetryPolicy.delayMs(2, 1.0))
        assertEquals(4_000L, DirconRetryPolicy.delayMs(3, 1.0))
        assertEquals(8_000L, DirconRetryPolicy.delayMs(4, 1.0))
        assertEquals(30_000L, DirconRetryPolicy.delayMs(5, 1.0))
        assertEquals(60_000L, DirconRetryPolicy.delayMs(6, 1.0))
        assertEquals(60_000L, DirconRetryPolicy.delayMs(99, 1.0))
    }

    @Test fun `jitter stays inside twenty percent`() {
        assertEquals(800L, DirconRetryPolicy.delayMs(1, 0.8))
        assertEquals(1_200L, DirconRetryPolicy.delayMs(1, 1.2))
        assertThrows(IllegalArgumentException::class.java) { DirconRetryPolicy.delayMs(1, 0.79) }
        assertThrows(IllegalArgumentException::class.java) { DirconRetryPolicy.delayMs(0, 1.0) }
    }
}
