package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Replays both endpoints and real strict v2 frames, including loss/reordering. */
class AcquisitionRecoveryTest {
    private class Clock(var now: Long = 1_000L) : ElapsedClock { override fun nowMs() = now }
    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private val a = id("a")
    private val b = id("b")
    private val headset = id("headset")
    private val sessionA = id("session-a")
    private val sessionB = id("session-b")
    private val members = setOf(a, b)
    private val revisions = mapOf(a to 7L, b to 9L)
    private fun status(peer: String, session: String, revision: Long) =
        PassiveAcquisitionCoordinator.PeerStatus(peer, session, revision, 2, true, headset,
            PeerOwnership.RELEASED, PeerAudioUse.IDLE)

    private inner class Pair {
        val clock = Clock()
        private var count = 0
        val requester = PassiveAcquisitionCoordinator(a, clock) { id("request-${++count}") }
        val participant = PassiveAcquisitionCoordinator(b, clock) { id("grant-${++count}") }
        val outgoing = PeerAcquisitionProtocol(a, { sessionA }, requester)
        val incoming = PeerAcquisitionProtocol(b, { sessionB }, participant)
        val peers = mapOf(b to status(b, sessionB, 9))
        fun receive(frame: PeerV2Frame.Frame) = incoming.receive(a, sessionA, frame,
            status(b, sessionB, 9), members, revisions)
        fun begin(): List<PeerV2Frame.Frame> = outgoing.begin(headset, members,
            true, true, true, true, 7, peers)
        fun ready(deliverAck: Boolean = true): PeerV2Frame.Frame {
            val request = begin().single()
            val grant = receive(request).replies.single()
            val start = outgoing.receive(b, sessionB, grant, status(a, sessionA, 7), members).replies.single()
            val ack = receive(start).replies.single()
            if (deliverAck) assertNotNull(outgoing.receive(b, sessionB, ack, status(a, sessionA, 7), members).readyToConnect)
            return ack
        }
        fun authorize(issue: Boolean = true): Boolean {
            val current = requester.currentIntent()!!
            return requester.authorizeConnect(current.requestId, current.generation,
                true, true, headset, members, peers, issue)
        }
    }

    @Test fun `recorded START ACK then cancelled before command no longer wedges peer`() {
        val pair = Pair()
        val delayedAck = pair.ready()
        assertTrue(pair.authorize(issue = false))
        assertFalse(pair.requester.currentIntent()!!.connectIssued)
        val ready = pair.requester.currentIntent()!!
        pair.outgoing.cancel().forEach(pair::receive)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, pair.participant.currentReservation()!!.phase)
        assertFalse(pair.requester.authorizeConnect(ready.requestId, ready.generation,
            true, true, headset, members, pair.peers))
        assertNull(pair.outgoing.receive(b, sessionB, delayedAck, status(a, sessionA, 7), members).readyToConnect)
        assertEquals(PeerV2Frame.Type.ACQUIRE_GRANT, pair.begin().flatMap { pair.receive(it).replies }.single().type)
    }

    @Test fun `lost START ACK expires pre-command intent and releases started participant`() {
        val pair = Pair()
        pair.ready(deliverAck = false)
        pair.clock.now += PassiveAcquisitionCoordinator.INTENT_TTL_MS
        val next = pair.begin()
        assertEquals(listOf(PeerV2Frame.Type.ACQUIRE_CANCEL, PeerV2Frame.Type.ACQUIRE_REQUEST), next.map { it.type })
        assertEquals(PeerV2Frame.Type.ACQUIRE_GRANT, next.flatMap { pair.receive(it).replies }.single().type)
    }

    @Test fun `lost cancellation is replayed before new request and old cancel cannot clear new grant`() {
        val pair = Pair()
        pair.ready()
        val lostCancel = pair.outgoing.cancel().single()
        val next = pair.begin()
        assertEquals(PeerV2Frame.Type.ACQUIRE_CANCEL, next.first().type)
        val grant = next.flatMap { pair.receive(it).replies }.single()
        assertEquals(PeerV2Frame.Type.ACQUIRE_GRANT, grant.type)
        val active = pair.participant.currentReservation()!!.requestId
        pair.receive(lostCancel)
        assertEquals(active, pair.participant.currentReservation()!!.requestId)
        assertEquals(PassiveAcquisitionCoordinator.Phase.PREPARING, pair.participant.currentReservation()!!.phase)
    }

    @Test fun `actual command stays fenced across time transport loss and lid cancellation`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        pair.clock.now += 3_600_000L
        assertTrue(pair.outgoing.cancel().isEmpty())
        assertTrue(pair.begin().isEmpty())
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, pair.requester.currentIntent()!!.phase)
        assertTrue(pair.outgoing.terminalProfileFailure().isEmpty())
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, pair.participant.currentReservation()!!.phase)
    }

    @Test fun `confirmed native failure releases both endpoints and fences every old callback`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        val old = pair.requester.currentIntent()!!
        pair.requester.observeConnecting()
        val failure = pair.outgoing.terminalProfileFailure()
        assertEquals(1, failure.size)
        failure.forEach(pair::receive)
        assertFalse(pair.requester.commandIsCurrent(old.requestId, old.generation))
        assertTrue(pair.outgoing.terminalProfileFailure().isEmpty())
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, pair.participant.currentReservation()!!.phase)
        assertEquals(PeerV2Frame.Type.ACQUIRE_GRANT, pair.begin().flatMap { pair.receive(it).replies }.single().type)
    }

    @Test fun `offline ownership resolves original participant without original acquisition ID`() {
        val pair = Pair()
        pair.ready()
        assertFalse(pair.participant.observePeerHeld(id("stranger"), headset))
        assertFalse(pair.participant.observePeerHeld(a, id("another-headset")))
        assertTrue(pair.participant.observePeerHeld(a, headset))
        assertFalse(pair.participant.observePeerHeld(a, headset))
        assertEquals(PassiveAcquisitionCoordinator.Phase.RESOLVED, pair.participant.currentReservation()!!.phase)
    }

    @Test fun `cancel must match request requester process session and membership`() {
        val pair = Pair()
        pair.ready()
        val current = pair.participant.currentReservation()!!
        assertFalse(pair.participant.participantCancel(id("wrong"), a, sessionA, current.membershipDigest))
        assertFalse(pair.participant.participantCancel(current.requestId, b, sessionA, current.membershipDigest))
        assertFalse(pair.participant.participantCancel(current.requestId, a, id("old-session"), current.membershipDigest))
        assertFalse(pair.participant.participantCancel(current.requestId, a, sessionA, "wrong-digest"))
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, pair.participant.currentReservation()!!.phase)
        assertTrue(pair.participant.participantCancel(current.requestId, a, sessionA, current.membershipDigest))
        assertFalse(pair.participant.participantCancel(current.requestId, a, sessionA, current.membershipDigest))
    }

    @Test fun `one command commitment only and duplicate callback cannot cancel issued attempt`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        assertFalse(pair.authorize())
        val current = pair.requester.currentIntent()!!
        assertTrue(pair.requester.commandIsCurrent(current.requestId, current.generation))
    }

    @Test fun `one phone cannot simultaneously request and grant competing reservations`() {
        val pair = Pair()
        pair.ready()
        val competing = pair.participant.begin(headset, sessionB, members, true, true, true, true,
            9, mapOf(a to status(a, sessionA, 7))) as PassiveAcquisitionCoordinator.Event.Deny
        assertEquals(PassiveAcquisitionCoordinator.Denial.BUSY, competing.reason)
        val participantResult = pair.requester.participantRequest(id("competing"), b, sessionB, headset,
            members.sorted(), PassiveAcquisitionCoordinator.membershipDigest(members.toList()), sessionA,
            status(a, sessionA, 7), members)
        assertEquals(PassiveAcquisitionCoordinator.Denial.BUSY, (participantResult as PassiveAcquisitionCoordinator.Event.Deny).reason)
    }

    @Test fun `successful requester completion is idempotent and clears its local lock`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        val current = pair.requester.currentIntent()!!
        assertTrue(pair.requester.requesterResolve(current.requestId, true))
        assertFalse(pair.requester.requesterResolve(current.requestId, true))
        assertTrue(pair.outgoing.cancel().isEmpty())
        assertFalse(pair.requester.commandIsCurrent(current.requestId, current.generation))
    }
    @Test fun `explicit OS rejection releases only the exact unstarted committed command`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        val current = pair.requester.currentIntent()!!
        assertTrue(pair.requester.rejectCommand(current.requestId, current.generation + 1).isEmpty())
        val terminal = pair.outgoing.rejectedCommand(PassiveAcquisitionCoordinator.Event.ReadyToConnect(
            current.requestId, headset, current.generation))
        assertEquals(1, terminal.size)
        terminal.forEach(pair::receive)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, pair.participant.currentReservation()!!.phase)
        assertFalse(pair.requester.commandIsCurrent(current.requestId, current.generation))
    }

    @Test fun `late rejection cannot release an attempt already observed connecting`() {
        val pair = Pair()
        pair.ready()
        assertTrue(pair.authorize())
        pair.requester.observeConnecting()
        val current = pair.requester.currentIntent()!!
        assertTrue(pair.requester.rejectCommand(current.requestId, current.generation).isEmpty())
        assertTrue(pair.requester.commandIsCurrent(current.requestId, current.generation))
    }

    @Test fun `lost one of three participant ACKs is cancelled on every participant`() {
        val clock = Clock()
        val c = id("c")
        val sessionC = id("session-c")
        val requester = PassiveAcquisitionCoordinator(a, clock) { id("three-request") }
        val bCoordinator = PassiveAcquisitionCoordinator(b, clock) { id("three-grant-b") }
        val cCoordinator = PassiveAcquisitionCoordinator(c, clock) { id("three-grant-c") }
        val out = PeerAcquisitionProtocol(a, { sessionA }, requester)
        val bp = PeerAcquisitionProtocol(b, { sessionB }, bCoordinator)
        val cp = PeerAcquisitionProtocol(c, { sessionC }, cCoordinator)
        val group = setOf(a, b, c)
        val revs = mapOf(a to 7L, b to 9L, c to 11L)
        val request = out.begin(headset, group, true, true, true, true, 7,
            mapOf(b to status(b, sessionB, 9), c to status(c, sessionC, 11))).single()
        val bg = bp.receive(a, sessionA, request, status(b, sessionB, 9), group, revs).replies.single()
        val cg = cp.receive(a, sessionA, request, status(c, sessionC, 11), group, revs).replies.single()
        assertTrue(out.receive(b, sessionB, bg, status(a, sessionA, 7), group).replies.isEmpty())
        val starts = out.receive(c, sessionC, cg, status(a, sessionA, 7), group).replies
        assertEquals(2, starts.size)
        starts.forEach { frame ->
            val grantor = PeerV2Message.decode(frame).fields["grantorId"]
            if (grantor == b) {
                val ack = bp.receive(a, sessionA, frame, status(b, sessionB, 9), group, revs).replies.single()
                assertNull(out.receive(b, sessionB, ack, status(a, sessionA, 7), group).readyToConnect)
            } else cp.receive(a, sessionA, frame, status(c, sessionC, 11), group, revs) // Lose C's ACK.
        }
        out.cancel().forEach { frame ->
            bp.receive(a, sessionA, frame, status(b, sessionB, 9), group, revs)
            cp.receive(a, sessionA, frame, status(c, sessionC, 11), group, revs)
        }
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, bCoordinator.currentReservation()!!.phase)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, cCoordinator.currentReservation()!!.phase)
    }

    @Test fun `failed callback validation publishes cancellation even after local transition already happened`() {
        val pair = Pair()
        pair.ready()
        val current = pair.requester.currentIntent()!!
        assertFalse(pair.requester.authorizeConnect(current.requestId, current.generation,
            false, true, headset, members, pair.peers, issueCommand = false))
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, pair.requester.currentIntent()!!.phase)
        assertTrue(pair.outgoing.cancel().isEmpty())
        val proof = pair.outgoing.replayCancellations()
        assertEquals(1, proof.size)
        proof.forEach(pair::receive)
        assertEquals(PassiveAcquisitionCoordinator.Phase.CANCELLED, pair.participant.currentReservation()!!.phase)
    }

}
