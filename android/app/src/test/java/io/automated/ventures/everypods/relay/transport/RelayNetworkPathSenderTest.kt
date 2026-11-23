/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayNetworkPathSenderTest {
    private val wlan0 = RelayNetworkPath("wlan0", "192.168.1.34", 24, 100L)
    private val wlan1 = RelayNetworkPath("wlan1", "192.168.1.45", 24, 200L)
    private val ap = RelayNetworkPath("ap0", "192.168.1.50", 24, 0L)

    @Test fun noCandidateGivesNothing() = assertNull(RelayNetworkPath.pickSender(emptyList(), 100L))

    @Test fun singleCandidateIsUsed() = assertEquals(wlan1, RelayNetworkPath.pickSender(listOf(wlan1), 100L))

    @Test fun dualWifiPrefersTheDefaultNetwork() =
        assertEquals(wlan1, RelayNetworkPath.pickSender(listOf(wlan0, wlan1), 200L))

    @Test fun dualWifiWithoutDefaultPicksARegisteredNetworkInStableOrder() {
        assertEquals(wlan0, RelayNetworkPath.pickSender(listOf(ap, wlan1, wlan0), null))
        assertEquals(wlan0, RelayNetworkPath.pickSender(listOf(wlan1, wlan0), 999L))
    }

    @Test fun unregisteredInterfacesAreTheLastResort() =
        assertEquals(ap, RelayNetworkPath.pickSender(listOf(ap, ap.copy(interfaceName = "swlan0")), null))
}
