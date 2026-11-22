package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.peer.PeerGattClientPolicy.DialSchedule
import io.automated.ventures.everypods.utils.peer.PeerGattClientPolicy.FailureKind
import io.automated.ventures.everypods.utils.peer.PeerGattClientPolicy.LinkIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGattClientPolicyTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"

    @Test fun backoffDoublesAndCaps() {
        val seq = (1..8).map { PeerGattClientPolicy.backoffMs(it) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), seq)
        assertEquals(60_000L, PeerGattClientPolicy.backoffMs(500))
        assertEquals(0L, PeerGattClientPolicy.backoffMs(0))
    }

    @Test fun failuresGrowBackoffPerAddressAndReadyResets() {
        val s = DialSchedule()
        assertTrue(s.canDial(a, 0))
        assertEquals(2_000L, s.recordFailure(a, 1_000))
        assertEquals(4_000L, s.recordFailure(a, 3_000))
        assertFalse(s.canDial(a, 6_999))
        assertTrue(s.canDial(a, 7_000))
        assertTrue(s.canDial(b, 0))
        s.recordReady(a)
        assertEquals(0, s.failureCount(a))
        assertTrue(s.canDial(a, 0))
        assertEquals(2_000L, s.recordFailure(a, 10_000))
    }

    @Test fun holdOffExpiresAndNeverShrinks() {
        val s = DialSchedule()
        s.holdOff(a, 1_000, 30_000)
        assertFalse(s.canDial(a, 30_999))
        assertTrue(s.canDial(a, 31_000))
        s.holdOff(a, 2_000, 1_000)
        assertFalse(s.canDial(a, 30_999))
        s.recordReady(a)  // ready does not lift a hold-off
        assertFalse(s.canDial(a, 30_999))
        assertTrue(s.canDial(b, 1_000))
    }

    @Test fun holdOffAndBackoffCombineToTheLater() {
        val s = DialSchedule()
        s.holdOff(a, 0, 1_000)
        s.recordFailure(a, 0)
        assertFalse(s.canDial(a, 1_999))
        assertTrue(s.canDial(a, 2_000))
    }

    @Test fun closeCooldownBlocksImmediateRedial() {
        val s = DialSchedule()
        s.noteClosed(a, 1_000)
        assertFalse(s.canDial(a, 1_499))
        assertTrue(s.canDial(a, 1_500))
        s.forget(a)
        assertTrue(s.canDial(a, 1_000))
    }

    @Test fun status133ClassifiedByElapsedTime() {
        assertEquals(FailureKind.STACK_ERROR, PeerGattClientPolicy.classify(133, 3_000))
        assertEquals(FailureKind.STACK_ERROR, PeerGattClientPolicy.classify(133, 19_999))
        assertEquals(FailureKind.TIMEOUT, PeerGattClientPolicy.classify(133, 20_000))
        assertEquals(FailureKind.TIMEOUT, PeerGattClientPolicy.classify(147, 25_000))
        assertEquals(FailureKind.STACK_ERROR, PeerGattClientPolicy.classify(147, 100))
        assertEquals(FailureKind.OTHER, PeerGattClientPolicy.classify(8, 30_000))
        assertEquals(FailureKind.OTHER, PeerGattClientPolicy.classify(0, 30_000))
    }

    @Test fun silenceDeadline() {
        assertFalse(PeerGattClientPolicy.isSilent(1_000, 36_000))
        assertTrue(PeerGattClientPolicy.isSilent(1_000, 36_001))
    }

    @Test fun pingAndKeepAliveIntervals() {
        assertFalse(PeerGattClientPolicy.pingDue(0, 9_999))
        assertTrue(PeerGattClientPolicy.pingDue(0, 10_000))
        assertFalse(PeerGattClientPolicy.keepAliveDue(0, 19_999))
        assertTrue(PeerGattClientPolicy.keepAliveDue(0, 20_000))
    }

    @Test fun staleLinkIdRejected() {
        val ids = LinkIds()
        val first = ids.next(a)
        assertTrue(ids.isCurrent(a, first))
        val second = ids.next(a)
        assertFalse(ids.isCurrent(a, first))
        assertTrue(ids.isCurrent(a, second))
        ids.release(a, first)  // stale release is a no-op
        assertTrue(ids.isCurrent(a, second))
        ids.release(a, second)
        assertFalse(ids.isCurrent(a, second))
    }

    @Test fun linkIdsAreUniqueAcrossAddresses() {
        val ids = LinkIds()
        val x = ids.next(a)
        val y = ids.next(b)
        assertTrue(x != y)
        assertFalse(ids.isCurrent(a, y))
    }
}
