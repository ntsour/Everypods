package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDeviceReconnectPolicyTest {
    @Test
    fun `passive peer recovery is blocked while local audio is connected`() {
        assertFalse(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = false,
            ),
        )
    }

    @Test
    fun `manual retry is allowed while local audio is connected`() {
        assertTrue(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = true,
            ),
        )
    }

    @Test
    fun `one reachability probe is allowed while preserving a live AirPods link`() {
        assertFalse(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = false,
            ),
        )
        assertTrue(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = false,
                reachabilityProbeRequested = true,
            ),
        )
    }

    @Test
    fun `passive recovery resumes after local audio disconnects`() {
        assertTrue(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = false,
                manualAttemptRequested = false,
            ),
        )
    }

    @Test
    fun `passive headset acquisition blocks unknown peers and a known holder`() {
        assertFalse(CrossDeviceReconnectPolicy.mayPassivelyAcquire(true, false, false))
        assertFalse(CrossDeviceReconnectPolicy.mayPassivelyAcquire(true, true, true))
        assertFalse(CrossDeviceReconnectPolicy.mayPassivelyAcquire(true, true, false))
        assertTrue(CrossDeviceReconnectPolicy.mayPassivelyAcquire(true, true, false, reservationGranted = true))
        assertTrue(CrossDeviceReconnectPolicy.mayPassivelyAcquire(false, false, false))
    }

    @Test
    fun `peer ownership observation has bounded monotonic freshness`() {
        assertTrue(CrossDeviceReconnectPolicy.observationIsFresh(100, 135, 35))
        assertFalse(CrossDeviceReconnectPolicy.observationIsFresh(100, 136, 35))
        assertFalse(CrossDeviceReconnectPolicy.observationIsFresh(100, 99, 35))
    }

    @Test
    fun `every configured peer must be connected and have fresh status`() {
        val peers = setOf("AA:00", "BB:00")
        val statuses = mapOf("aa:00" to 100L, "BB:00" to 100L)
        assertFalse(CrossDeviceReconnectPolicy.allPeersHaveFreshStatus(peers, setOf("AA:00"), statuses, 120, 35))
        assertFalse(CrossDeviceReconnectPolicy.allPeersHaveFreshStatus(peers, peers, mapOf("AA:00" to 100L), 120, 35))
        assertFalse(CrossDeviceReconnectPolicy.allPeersHaveFreshStatus(peers, peers, statuses, 136, 35))
        assertTrue(CrossDeviceReconnectPolicy.allPeersHaveFreshStatus(peers, peers, statuses, 135, 35))
        assertTrue(CrossDeviceReconnectPolicy.allPeersHaveFreshStatus(emptySet(), emptySet(), emptyMap(), 0, 35))
    }
}
