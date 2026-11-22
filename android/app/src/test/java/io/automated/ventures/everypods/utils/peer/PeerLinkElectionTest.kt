/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLinkElectionTest {
    private val small = "1b2c3d4e-0000-4000-8000-000000000001"
    private val large = "9f8e7d6c-0000-4000-8000-000000000002"

    @Test fun unknownPeerIsDialledByBothSides() {
        assertTrue(PeerLinkElection.shouldDial(small, null))
        assertTrue(PeerLinkElection.shouldDial(large, null))
    }

    @Test fun knownPeerIsDialledOnlyBySmallerId() {
        assertTrue(PeerLinkElection.shouldDial(small, large))
        assertFalse(PeerLinkElection.shouldDial(large, small))
    }

    @Test fun exactlyOneSideKeepsItsCentralLink() {
        assertTrue(PeerLinkElection.keepCentralLink(small, large))
        assertFalse(PeerLinkElection.keepCentralLink(large, small))
    }

    @Test fun comparisonIgnoresCase() {
        assertTrue(PeerLinkElection.keepCentralLink(small.uppercase(), large))
        assertFalse(PeerLinkElection.keepCentralLink(large, small.uppercase()))
    }

    @Test fun equalIdsElectNobody() {
        assertFalse(PeerLinkElection.keepCentralLink(small, small.uppercase()))
        assertFalse(PeerLinkElection.shouldDial(small, small))
    }

    @Test fun rfcommFallbackOnlyForPhonesNeverSeenOverLe() {
        assertTrue(PeerLinkElection.needsRfcommFallback(null))
        assertFalse(PeerLinkElection.needsRfcommFallback(large))
    }
}
