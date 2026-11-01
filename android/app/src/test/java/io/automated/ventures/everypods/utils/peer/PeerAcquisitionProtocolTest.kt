package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class PeerAcquisitionProtocolTest {
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val headset = "00000000-0000-4000-8000-000000000003"
    private val sessionA = "00000000-0000-4000-8000-000000000011"
    private val sessionB = "00000000-0000-4000-8000-000000000012"

    private class Clock(var now: Long = 100L) : ElapsedClock { override fun nowMs() = now }

    private fun status(id: String, session: String, revision: Long = 1) =
        PassiveAcquisitionCoordinator.PeerStatus(id, session, revision, 2, true, headset,
            PeerOwnership.RELEASED, PeerAudioUse.IDLE)

    private fun protocol(id: String, session: String) = PeerAcquisitionProtocol(id, { session },
        PassiveAcquisitionCoordinator(id, Clock()) {
            if (id == a) "00000000-0000-4000-8000-000000000098" else "00000000-0000-4000-8000-000000000099"
        })

    @Test fun `reservation exchange reaches ready only after every grant and start ack`() {
        val requester = protocol(a, sessionA)
        val participant = protocol(b, sessionB)
        val membership = setOf(a, b)
        val request = requester.begin(headset, membership, localUnowned = true,
            lastOwnerEligible = true, lidOpen = true, enabled = true, localSnapshotRevision = 7,
            peers = mapOf(b to status(b, sessionB, 9))).single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_REQUEST, request.type)

        val snapshotRevisions = mapOf(a to 7L, b to 9L)
        val grant = participant.receive(a, sessionA, request, status(b, sessionB, 9), membership,
            snapshotRevisions).replies.single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_GRANT, grant.type)
        val start = requester.receive(b, sessionB, grant, status(a, sessionA), membership).replies.single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_START, start.type)

        val ack = participant.receive(a, sessionA, start, status(b, sessionB, 9), membership,
            snapshotRevisions).replies.single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_START_ACK, ack.type)
        val ready = requester.receive(b, sessionB, ack, status(a, sessionA), membership)
        assertNotNull(ready.readyToConnect)
        assertTrue(ready.replies.isEmpty())
    }

    @Test fun `wire identity must match authenticated v2 peer`() {
        val requester = protocol(a, sessionA)
        val request = requester.begin(headset, setOf(a, b), true, true, true, true, 1,
            mapOf(b to status(b, sessionB))).single()
        val participant = protocol(b, sessionB)
        assertThrows(IllegalArgumentException::class.java) {
            participant.receive("00000000-0000-4000-8000-000000000004", sessionA,
                request, status(b, sessionB), setOf(a, b))
        }
    }

    @Test fun `participant denies a request with stale snapshot revisions`() {
        val requester = protocol(a, sessionA)
        val request = requester.begin(headset, setOf(a, b), true, true, true, true, 7,
            mapOf(b to status(b, sessionB, 9))).single()
        val participant = protocol(b, sessionB)
        val deny = participant.receive(a, sessionA, request, status(b, sessionB, 9), setOf(a, b),
            mapOf(a to 6L, b to 9L)).replies.single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_DENY, deny.type)
        assertEquals("STALE_SNAPSHOT", PeerV2Message.decode(deny).fields["reason"])
    }
    @Test fun `duplicate request and start are idempotent across two protocol instances`() {
        val requester = protocol(a, sessionA)
        val participant = protocol(b, sessionB)
        val members = setOf(a, b)
        val revisions = mapOf(a to 1L, b to 1L)
        val request = requester.begin(headset, members, true, true, true, true, 1,
            mapOf(b to status(b, sessionB))).single()
        val grant = participant.receive(a, sessionA, request, status(b, sessionB), members, revisions).replies.single()
        val duplicateGrant = participant.receive(a, sessionA, request, status(b, sessionB), members, revisions).replies.single()
        assertEquals(PeerV2Message.decode(grant).fields, PeerV2Message.decode(duplicateGrant).fields)
        val start = requester.receive(b, sessionB, grant, status(a, sessionA), members).replies.single()
        assertTrue(requester.receive(b, sessionB, duplicateGrant, status(a, sessionA), members).replies.isEmpty())
        val ack = participant.receive(a, sessionA, start, status(b, sessionB), members, revisions).replies.single()
        val duplicateAck = participant.receive(a, sessionA, start, status(b, sessionB), members, revisions).replies.single()
        assertEquals(PeerV2Message.decode(ack).fields, PeerV2Message.decode(duplicateAck).fields)
        assertNotNull(requester.receive(b, sessionB, ack, status(a, sessionA), members).readyToConnect)
        assertNull(requester.receive(b, sessionB, duplicateAck, status(a, sessionA), members).readyToConnect)
    }

    @Test fun `a restarted peer cannot complete the old session reservation`() {
        val coordinator = PassiveAcquisitionCoordinator(a, Clock()) { "00000000-0000-4000-8000-000000000099" }
        val requester = PeerAcquisitionProtocol(a, { sessionA }, coordinator)
        val participant = protocol(b, sessionB)
        val members = setOf(a, b)
        val revisions = mapOf(a to 1L, b to 1L)
        val request = requester.begin(headset, members, true, true, true, true, 1,
            mapOf(b to status(b, sessionB))).single()
        val grant = participant.receive(a, sessionA, request, status(b, sessionB), members, revisions).replies.single()
        val start = requester.receive(b, sessionB, grant, status(a, sessionA), members).replies.single()
        val ack = participant.receive(a, sessionA, start, status(b, sessionB), members, revisions).replies.single()
        val ready = requester.receive(b, sessionB, ack, status(a, sessionA), members).readyToConnect!!
        val restartedSession = "00000000-0000-4000-8000-000000000013"
        assertFalse(coordinator.authorizeConnect(ready.requestId, ready.generation, true, true, headset,
            members, mapOf(b to status(b, restartedSession)), 1))
    }

    @Test fun `simultaneous passive requests elect the same lower installation regardless of delivery order`() {
        for (deliverToLowerFirst in listOf(true, false)) {
            val lower = protocol(a, sessionA)
            val higher = protocol(b, sessionB)
            val members = setOf(a, b)
            val revisions = mapOf(a to 1L, b to 1L)
            val requestA = lower.begin(headset, members, true, true, true, true, 1,
                mapOf(b to status(b, sessionB))).single()
            val requestB = higher.begin(headset, members, true, true, true, true, 1,
                mapOf(a to status(a, sessionA))).single()
            fun toLower() = lower.receive(b, sessionB, requestB, status(a, sessionA), members, revisions)
            fun toHigher() = higher.receive(a, sessionA, requestA, status(b, sessionB), members, revisions)
            val lowerReply: PeerAcquisitionProtocol.Result
            val higherReply: PeerAcquisitionProtocol.Result
            if (deliverToLowerFirst) { lowerReply = toLower(); higherReply = toHigher() }
            else { higherReply = toHigher(); lowerReply = toLower() }
            assertEquals(PeerV2Frame.Type.ACQUIRE_DENY, lowerReply.replies.single().type)
            assertEquals(setOf(PeerV2Frame.Type.ACQUIRE_CANCEL, PeerV2Frame.Type.ACQUIRE_GRANT),
                higherReply.replies.map { it.type }.toSet())
            higher.receive(a, sessionA, lowerReply.replies.single(), status(b, sessionB), members, revisions)
            val cancel = higherReply.replies.single { it.type == PeerV2Frame.Type.ACQUIRE_CANCEL }
            assertNull(lower.receive(b, sessionB, cancel, status(a, sessionA), members, revisions).readyToConnect)
            val grant = higherReply.replies.single { it.type == PeerV2Frame.Type.ACQUIRE_GRANT }
            val start = lower.receive(b, sessionB, grant, status(a, sessionA), members, revisions).replies.single()
            val ack = higher.receive(a, sessionA, start, status(b, sessionB), members, revisions).replies.single()
            assertNotNull(lower.receive(b, sessionB, ack, status(a, sessionA), members, revisions).readyToConnect)
        }
    }

    @Test fun `three peers retain reservation on lost ack until authenticated cancel arrives`() {
        val c = "00000000-0000-4000-8000-000000000004"
        val sessionC = "00000000-0000-4000-8000-000000000014"
        var requestCounter = 100
        val requesterState = PassiveAcquisitionCoordinator(a, Clock()) {
            "00000000-0000-4000-8000-%012d".format(requestCounter++)
        }
        val stateB = PassiveAcquisitionCoordinator(b, Clock()) { "00000000-0000-4000-8000-000000000201" }
        val stateC = PassiveAcquisitionCoordinator(c, Clock()) { "00000000-0000-4000-8000-000000000202" }
        val requester = PeerAcquisitionProtocol(a, { sessionA }, requesterState)
        val peerB = PeerAcquisitionProtocol(b, { sessionB }, stateB)
        val peerC = PeerAcquisitionProtocol(c, { sessionC }, stateC)
        val members = setOf(a, b, c)
        val revisions = members.associateWith { 1L }
        val statuses = mapOf(b to status(b, sessionB), c to status(c, sessionC))
        val request = requester.begin(headset, members, true, true, true, true, 1, statuses).single()
        val grantB = peerB.receive(a, sessionA, request, status(b, sessionB), members, revisions).replies.single()
        val grantC = peerC.receive(a, sessionA, request, status(c, sessionC), members, revisions).replies.single()
        assertTrue(requester.receive(b, sessionB, grantB, status(a, sessionA), members, revisions).replies.isEmpty())
        val starts = requester.receive(c, sessionC, grantC, status(a, sessionA), members, revisions).replies
        assertEquals(2, starts.size)
        fun startFor(peer: String) = starts.single { PeerV2Message.decode(it).fields["grantorId"] == peer }
        val ackB = peerB.receive(a, sessionA, startFor(b), status(b, sessionB), members, revisions).replies.single()
        val lostAckC = peerC.receive(a, sessionA, startFor(c), status(c, sessionC), members, revisions).replies.single()
        assertNull(requester.receive(b, sessionB, ackB, status(a, sessionA), members, revisions).readyToConnect)
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, stateB.currentReservation()!!.phase)
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, stateC.currentReservation()!!.phase)
        val cancel = requester.cancel().single()
        // A partition does not release the participant just because its ACK was lost.
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, stateC.currentReservation()!!.phase)
        assertNull(requester.receive(c, sessionC, lostAckC, status(a, sessionA), members, revisions).readyToConnect)
        peerB.receive(a, sessionA, cancel, status(b, sessionB), members, revisions)
        peerC.receive(a, sessionA, cancel, status(c, sessionC), members, revisions)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, stateB.currentReservation()!!.phase)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, stateC.currentReservation()!!.phase)
        val retry = requester.begin(headset, members, true, true, true, true, 1, statuses)
        assertTrue(retry.any { it.type == PeerV2Frame.Type.ACQUIRE_REQUEST })
    }

}
