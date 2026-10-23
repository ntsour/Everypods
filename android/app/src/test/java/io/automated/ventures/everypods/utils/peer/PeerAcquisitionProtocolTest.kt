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
        PassiveAcquisitionCoordinator(id, Clock()) { "00000000-0000-4000-8000-000000000099" })

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
}
