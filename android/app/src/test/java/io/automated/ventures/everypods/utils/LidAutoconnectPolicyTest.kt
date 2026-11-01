package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidAutoconnectPolicyTest {
    @Test fun `restarted scan gets grace despite old advertisement`() {
        assertFalse(LidAutoconnectPolicy.isScanSilent(60_000, 50_000, 1_000, 45_000))
        assertFalse(LidAutoconnectPolicy.isScanSilent(94_999, 50_000, 1_000, 45_000))
        assertTrue(LidAutoconnectPolicy.isScanSilent(95_000, 50_000, 1_000, 45_000))
        assertFalse(LidAutoconnectPolicy.isScanSilent(95_000, 50_000, 80_000, 45_000))
        assertTrue(LidAutoconnectPolicy.isScanSilent(95_000, 0, 0, 45_000))
    }

    @Test fun `old scan batches cannot revive or roll back case state`() {
        assertTrue(LidAutoconnectPolicy.mayAcceptBleScan(20_000, 20_100, null))
        assertTrue(LidAutoconnectPolicy.mayAcceptBleScan(20_000, 20_100, 20_000))
        assertFalse(LidAutoconnectPolicy.mayAcceptBleScan(9_999, 20_000, null))
        assertFalse(LidAutoconnectPolicy.mayAcceptBleScan(19_999, 20_100, 20_000))
        assertFalse(LidAutoconnectPolicy.mayAcceptBleScan(0, 20_000, null))
        assertFalse(LidAutoconnectPolicy.mayAcceptBleScan(20_101, 20_100, null))
    }

    @Test fun `verified fresh BLE availability can trigger non AACP last holder recovery`() {
        fun allowed(observed: Long = 9_000L, now: Long = 10_000L, verified: Boolean = true,
                    lidOpen: Boolean = false, leftInEar: Boolean = true, rightInEar: Boolean = false,
                    stored: Boolean = false, enabled: Boolean = true, holder: Boolean = true,
                    connected: Boolean = false) = LidAutoconnectPolicy.mayStartFromBleAvailability(
            observed, now, verified, lidOpen, leftInEar, rightInEar, stored, enabled, holder, connected)

        assertTrue(allowed())
        assertTrue(allowed(leftInEar = false, lidOpen = true))
        assertTrue(allowed(leftInEar = false, rightInEar = true))
        assertFalse(allowed(verified = false))
        assertFalse(allowed(observed = 0))
        assertFalse(allowed(observed = 1, now = 20_000))
        assertFalse(allowed(leftInEar = false))
        assertFalse(allowed(stored = true))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(holder = false))
        assertFalse(allowed(connected = true))
    }

    @Test fun `BLE storage requires fresh closed noncontradictory valid observations`() {
        fun stored(observed: Long = 1_000, now: Long = 2_000, open: Boolean = false,
                   left: Boolean = true, right: Boolean = true, inEar: Boolean = false,
                   valid: Boolean = true) = LidAutoconnectPolicy.bleConfirmsStored(
            observed, now, open, left, right, inEar, false, valid)
        assertTrue(stored())
        assertFalse(stored(now = 12_001))
        assertFalse(stored(observed = 0))
        assertFalse(stored(observed = 3_000))
        assertFalse(stored(open = true))
        assertFalse(stored(left = false))
        assertFalse(stored(right = false))
        assertFalse(stored(inEar = true))
        assertFalse(stored(valid = false))
    }

    @Test fun `open case remains eligible while charging buds are present`() {
        assertFalse(LidAutoconnectPolicy.storedBudsCancel(true, true, true, false))
        assertTrue(LidAutoconnectPolicy.storedBudsCancel(true, false, false, false))
        assertTrue(LidAutoconnectPolicy.storedBudsCancel(false, true, false, false))
        assertFalse(LidAutoconnectPolicy.storedBudsCancel(false, true, false, true))
    }

    @Test fun `closed empty case keeps only a bounded fresh removal intent`() {
        assertTrue(LidAutoconnectPolicy.bleCanKeepRemovalIntent(1_000, 5_000, 6_000, false))
        assertFalse(LidAutoconnectPolicy.bleCanKeepRemovalIntent(1_000, 11_001, 11_001, false))
        assertFalse(LidAutoconnectPolicy.bleCanKeepRemovalIntent(1_000, 5_000, 6_000, true))
        assertFalse(LidAutoconnectPolicy.bleCanKeepRemovalIntent(1_000, 0, 6_000, false))
        assertFalse(LidAutoconnectPolicy.bleCanKeepRemovalIntent(0, 5_000, 6_000, false))
    }

    @Test fun `duplicate case advertisements do not restart active episode`() {
        assertTrue(LidAutoconnectPolicy.isDuplicateActiveEpisode(100, 200))
        // The deadline stays active after success, failure or a skipped attempt.
        assertTrue(LidAutoconnectPolicy.isDuplicateActiveEpisode(199, 200))
        assertFalse(LidAutoconnectPolicy.isDuplicateActiveEpisode(200, 200))
        assertFalse(LidAutoconnectPolicy.isDuplicateActiveEpisode(100, 0))
    }

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
