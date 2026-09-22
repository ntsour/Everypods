package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidAutoconnectPolicyTest {
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
