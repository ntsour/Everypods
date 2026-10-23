package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerRetryPolicyTest {
    @Test
    fun `backoff is bounded and jitter stays within configured interval`() {
        assertEquals(1_600L, PeerRetryPolicy.delayMs(0, false, 0.8))
        assertEquals(4_000L, PeerRetryPolicy.delayMs(1, false, 1.0))
        assertEquals(18_000L, PeerRetryPolicy.delayMs(20, false, 1.2))
    }

    @Test
    fun `audio retries never page faster than fifteen seconds`() {
        assertEquals(15_000L, PeerRetryPolicy.delayMs(0, true, 1.0))
        assertEquals(18_000L, PeerRetryPolicy.delayMs(3, true, 1.2))
    }

    @Test
    fun `invalid retry input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PeerRetryPolicy.delayMs(-1, false, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { PeerRetryPolicy.delayMs(0, false, 1.3) }
    }

    @Test
    fun `permission loss waits for a user correction instead of retrying`() {
        assertEquals(true, PeerRetryPolicy.requiresUserCorrection(PeerConnectionError.PERMISSION_REQUIRED))
        assertEquals(false, PeerRetryPolicy.requiresUserCorrection(PeerConnectionError.REMOTE_UNAVAILABLE))
        assertEquals(false, PeerRetryPolicy.requiresUserCorrection(null))
    }
}
