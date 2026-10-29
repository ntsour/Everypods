package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidAutoconnectPolicyTest {
    @Test fun `deferred callbacks require the same live cycle without a competing owner`() {
        fun allowed(requested: Long = 15_000L, activeDeadline: Long = 15_000L,
                    active: Boolean = true, enabled: Boolean = true, peer: Boolean = false,
                    peerCooldown: Long = 0L, contention: Long = 0L, now: Long = 1_000L) =
            LidAutoconnectPolicy.mayIssueDeferredConnect(now, requested, activeDeadline,
                active, enabled, peer, peerCooldown, contention)
        assertTrue(allowed())
        assertFalse(allowed(active = false))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(peer = true))
        assertFalse(allowed(activeDeadline = 16_000L))
        assertFalse(allowed(peerCooldown = 2_000L))
        assertFalse(allowed(contention = 2_000L))
        assertFalse(allowed(now = 15_000L))
    }

    @Test fun `removing buds then closing case preserves bounded recovery`() {
        assertTrue(LidAutoconnectPolicy.preserveRecoveryAfterClose(1_000L, 15_000L, false))
        assertFalse(LidAutoconnectPolicy.preserveRecoveryAfterClose(1_000L, 15_000L, true))
        assertFalse(LidAutoconnectPolicy.preserveRecoveryAfterClose(15_000L, 15_000L, false))
        assertFalse(LidAutoconnectPolicy.preserveRecoveryAfterClose(20_000L, 0L, false))
    }

    @Test
    fun `short lived A2DP connection after lid attempt is contested`() {
        assertTrue(
            LidAutoconnectPolicy.isContestedConnection(
                lidAttemptAtMs = 1_000L,
                a2dpConnectedAtMs = 1_400L,
                a2dpDisconnectedAtMs = 2_100L,
            )
        )
    }

    @Test
    fun `ordinary disconnect is not treated as contested lid connection`() {
        assertFalse(
            LidAutoconnectPolicy.isContestedConnection(
                lidAttemptAtMs = 1_000L,
                a2dpConnectedAtMs = 1_400L,
                a2dpDisconnectedAtMs = 10_000L,
            )
        )
        assertFalse(
            LidAutoconnectPolicy.isContestedConnection(
                lidAttemptAtMs = 0L,
                a2dpConnectedAtMs = 1_400L,
                a2dpDisconnectedAtMs = 2_100L,
            )
        )
    }
}
