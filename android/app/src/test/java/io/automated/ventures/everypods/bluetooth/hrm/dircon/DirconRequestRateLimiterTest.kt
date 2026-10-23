package io.automated.ventures.everypods.bluetooth.hrm.dircon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirconRequestRateLimiterTest {
    @Test fun `allows configured burst then closes budget until refill`() {
        val limiter = DirconRequestRateLimiter(nowElapsedMs = 10L)
        repeat(200) { assertTrue("request $it", limiter.tryAcquire(10L)) }
        assertFalse(limiter.tryAcquire(10L))
        assertTrue(limiter.tryAcquire(20L)) // One token refills in ten milliseconds.
    }

    @Test fun `refill is capped at burst capacity`() {
        val limiter = DirconRequestRateLimiter(capacity = 2.0, requestsPerSecond = 1.0, nowElapsedMs = 0L)
        assertTrue(limiter.tryAcquire(10_000L))
        assertTrue(limiter.tryAcquire(10_000L))
        assertFalse(limiter.tryAcquire(10_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non monotonic clock`() {
        DirconRequestRateLimiter(nowElapsedMs = 10L).tryAcquire(9L)
    }
}
