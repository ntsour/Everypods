package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class PassiveAcquisitionCoordinatorTest {
    private class Clock(var now: Long = 1_000L) : ElapsedClock { override fun nowMs() = now }
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val headset = "00000000-0000-4000-8000-000000000099"

    private fun status(id: String, session: String, headsetId: String? = headset,
                       ownership: PeerOwnership = PeerOwnership.RELEASED) =
        PassiveAcquisitionCoordinator.PeerStatus(id, session, 3, 2, true, headsetId, ownership, PeerAudioUse.IDLE)

    @Test fun `released peer must be idle and uncertain or active audio cannot grant acquisition`() {
        for (use in PeerAudioUse.values()) {
            val coordinator = PassiveAcquisitionCoordinator(a, Clock())
            val peer = status(b, "sess-b").copy(audioUse = use)
            val result = coordinator.begin(headset, "sess-a", setOf(a, b),
                true, true, true, true, 4, mapOf(b to peer))
            assertEquals("audio use $use", use == PeerAudioUse.IDLE,
                result is PassiveAcquisitionCoordinator.Event.Request)
        }
    }

    @Test fun `requester waits for full grant and start ack quorum before connect`() {
        val clock = Clock()
        val coordinator = PassiveAcquisitionCoordinator(a, clock) { "00000000-0000-4000-8000-000000000010" }
        val request = coordinator.begin(headset, "00000000-0000-4000-8000-000000000011", setOf(a, b),
            localUnowned = true, lastOwnerEligible = true, lidOpen = true, enabled = true,
            localSnapshotRevision = 4,
            peers = mapOf(b to status(b, "00000000-0000-4000-8000-000000000012"))) as PassiveAcquisitionCoordinator.Event.Request
        assertTrue(coordinator.onGrant(b, "00000000-0000-4000-8000-000000000012", request.requestId, "00000000-0000-4000-8000-000000000013").single() is PassiveAcquisitionCoordinator.Event.Start)
        val ready = coordinator.onStartAck(b, request.requestId, "00000000-0000-4000-8000-000000000013")
        val intentGeneration = coordinator.currentIntent()!!.generation
        assertEquals(PassiveAcquisitionCoordinator.Event.ReadyToConnect(request.requestId, headset, intentGeneration), ready)
        assertTrue(coordinator.authorizeConnect(request.requestId, intentGeneration, true, true, headset, setOf(a, b),
            mapOf(b to status(b, "00000000-0000-4000-8000-000000000012"))))
    }

    @Test fun `missing peer blocks request and expired or mismatched grant is ignored`() {
        val clock = Clock()
        val coordinator = PassiveAcquisitionCoordinator(a, clock) { "00000000-0000-4000-8000-000000000020" }
        assertEquals(PassiveAcquisitionCoordinator.Denial.MEMBERSHIP_MISMATCH,
            (coordinator.begin(headset, "sess-a", setOf(a, b), true, true, true, true, 4, emptyMap()) as
                PassiveAcquisitionCoordinator.Event.Deny).reason)
        val request = coordinator.begin(headset, "sess-a", setOf(a, b), true, true, true, true, 4,
            mapOf(b to status(b, "sess-b"))) as PassiveAcquisitionCoordinator.Event.Request
        assertTrue(coordinator.onGrant(b, "sess-b", "wrong-request", "grant") .isEmpty())
        clock.now += PassiveAcquisitionCoordinator.GRANT_RECEIVE_MS + 1
        assertTrue(coordinator.onGrant(b, "sess-b", request.requestId, "grant").isEmpty())
    }

    @Test fun `participant holds in progress reservation until definite resolution`() {
        val clock = Clock()
        val coordinator = PassiveAcquisitionCoordinator(b, clock) { "00000000-0000-4000-8000-000000000030" }
        val members = listOf(a, b)
        val digest = PassiveAcquisitionCoordinator.membershipDigest(members)
        val grant = coordinator.participantRequest("req", a, "sess-a", headset, members, digest, "sess-b",
            status(b, "sess-b"), setOf(a, b)) as PassiveAcquisitionCoordinator.Event.Grant
        assertEquals("req", grant.requestId)
        assertTrue(coordinator.participantStart("req", a, "sess-a", grant.grantId, "old-session") is PassiveAcquisitionCoordinator.Event.Deny)
        assertTrue(coordinator.participantStart("req", a, "sess-a", grant.grantId, "sess-b") is PassiveAcquisitionCoordinator.Event.StartAck)
        clock.now += 60_000
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, coordinator.currentReservation()?.phase)
        assertFalse(coordinator.participantResolve("req", false))
        assertTrue(coordinator.participantRequest("req2", a, "sess-a", headset, members, digest, "sess-b",
            status(b, "sess-b"), setOf(a, b)) is PassiveAcquisitionCoordinator.Event.Deny)
        assertTrue(coordinator.participantResolve("req", true))
    }

    @Test fun `issued headset command reservation cannot expire be cancelled or replaced`() {
        val clock = Clock()
        var nextId = 0
        val coordinator = PassiveAcquisitionCoordinator(a, clock) { "request-${++nextId}" }
        val peerStatus = status(b, "sess-b")
        val request = coordinator.begin(headset, "sess-a", setOf(a, b), true, true, true, true, 4,
            mapOf(b to peerStatus)) as PassiveAcquisitionCoordinator.Event.Request
        val start = coordinator.onGrant(b, "sess-b", request.requestId, "grant")
        assertEquals(1, start.size)
        val ready = coordinator.onStartAck(b, request.requestId, "grant") as
            PassiveAcquisitionCoordinator.Event.ReadyToConnect
        assertTrue(coordinator.authorizeConnect(ready.requestId, ready.generation, true, true, headset, setOf(a, b),
            mapOf(b to peerStatus)))
        assertTrue(coordinator.currentIntent()!!.connectIssued)

        clock.now += 60_000L
        assertTrue(coordinator.cancelIntent().isEmpty())
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, coordinator.currentIntent()!!.phase)
        val second = coordinator.begin(headset, "sess-a", setOf(a, b), true, true, true, true, 5,
            mapOf(b to peerStatus)) as PassiveAcquisitionCoordinator.Event.Deny
        assertEquals(PassiveAcquisitionCoordinator.Denial.BUSY, second.reason)
        assertEquals(request.requestId, coordinator.currentIntent()!!.requestId)
    }

    @Test fun `ready reservation can still be cancelled before headset command`() {
        val coordinator = PassiveAcquisitionCoordinator(a, Clock()) { "request" }
        val request = coordinator.begin(headset, "sess-a", setOf(a, b), true, true, true, true, 4,
            mapOf(b to status(b, "sess-b"))) as PassiveAcquisitionCoordinator.Event.Request
        coordinator.onGrant(b, "sess-b", request.requestId, "grant")
        val ready = coordinator.onStartAck(b, request.requestId, "grant") as
            PassiveAcquisitionCoordinator.Event.ReadyToConnect

        assertFalse(coordinator.currentIntent()!!.connectIssued)
        assertTrue(coordinator.cancelIntent().single() is PassiveAcquisitionCoordinator.Event.Cancel)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, coordinator.currentIntent()!!.phase)
        assertFalse(coordinator.authorizeConnect(ready.requestId, ready.generation, true, true, headset,
            setOf(a, b), mapOf(b to status(b, "sess-b"))))
    }

    @Test fun `expired preparation cannot be replayed and duplicate START is idempotent`() {
        val clock = Clock()
        val coordinator = PassiveAcquisitionCoordinator(b, clock) { "00000000-0000-4000-8000-000000000040" }
        val members = listOf(a, b)
        val digest = PassiveAcquisitionCoordinator.membershipDigest(members)
        val status = status(b, "sess-b")
        val grant = coordinator.participantRequest("request-1", a, "sess-a", headset, members, digest,
            "sess-b", status, setOf(a, b)) as PassiveAcquisitionCoordinator.Event.Grant
        clock.now += PassiveAcquisitionCoordinator.PREPARATION_TTL_MS
        assertEquals(PassiveAcquisitionCoordinator.Denial.EXPIRED,
            (coordinator.participantStart("request-1", a, "sess-a", grant.grantId, "sess-b") as
                PassiveAcquisitionCoordinator.Event.Deny).reason)
        val next = coordinator.participantRequest("request-2", a, "sess-a", headset, members, digest,
            "sess-b", status, setOf(a, b)) as PassiveAcquisitionCoordinator.Event.Grant
        val ack = coordinator.participantStart("request-2", a, "sess-a", next.grantId, "sess-b")
        assertEquals(ack, coordinator.participantStart("request-2", a, "sess-a", next.grantId, "sess-b"))
        assertTrue(coordinator.participantStart("request-2", a, "sess-a", next.grantId, "sess-b",
            status(b, "sess-b", ownership = PeerOwnership.HELD), membership = members,
            configuredMembership = setOf(a, b)) is PassiveAcquisitionCoordinator.Event.Deny)
        assertEquals(PassiveAcquisitionCoordinator.Denial.BUSY,
            (coordinator.participantRequest("request-3", a, "sess-a", headset, members, digest,
                "sess-b", status, setOf(a, b)) as PassiveAcquisitionCoordinator.Event.Deny).reason)
    }

    @Test fun `START revalidates the exact snapshot revision set`() {
        val coordinator = PassiveAcquisitionCoordinator(b, Clock()) { "00000000-0000-4000-8000-000000000050" }
        val members = listOf(a, b)
        val digest = PassiveAcquisitionCoordinator.membershipDigest(members)
        val revisions = mapOf(a to 7L, b to 9L)
        val grant = coordinator.participantRequest("request", a, "sess-a", headset, members, digest, "sess-b",
            status(b, "sess-b"), setOf(a, b), revisions, revisions) as PassiveAcquisitionCoordinator.Event.Grant
        val stale = coordinator.participantStart("request", a, "sess-a", grant.grantId, "sess-b",
            status(b, "sess-b"), revisions + (b to 10L), members, setOf(a, b))
        assertEquals(PassiveAcquisitionCoordinator.Denial.STALE_SNAPSHOT,
            (stale as PassiveAcquisitionCoordinator.Event.Deny).reason)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, coordinator.currentReservation()?.phase)
    }
}
