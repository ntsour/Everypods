package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class HandoverGrantPolicyTest {
    private val grant = GrantDecision.Grant
    private fun deny(r: GrantDenial) = GrantDecision.Deny(r)
    private fun holder(use: PeerAudioUse, since: Long? = null, holds: Boolean = true) =
        HolderState(holds, use, since)
    private fun decide(i: HandoverIntent, h: HolderState) = HandoverGrantPolicy.decide(i, h)

    @Test fun `non-holder grants every intent and audio use`() {
        for (i in HandoverIntent.entries) for (u in PeerAudioUse.entries) {
            assertEquals("$i $u", grant, decide(i, holder(u, 0, holds = false)))
        }
    }

    @Test fun `passive is denied HELD for a holder in every non-call audio state`() {
        for (u in PeerAudioUse.entries.filter { it != PeerAudioUse.CALL }) {
            assertEquals(deny(GrantDenial.HELD), decide(HandoverIntent.PASSIVE, holder(u)))
        }
    }

    @Test fun `passive is HELD even during a call`() {
        assertEquals(deny(GrantDenial.HELD), decide(HandoverIntent.PASSIVE, holder(PeerAudioUse.CALL)))
    }

    @Test fun `call veto applies to every non-passive intent`() {
        for (i in HandoverIntent.entries.filter { it != HandoverIntent.PASSIVE }) {
            assertEquals("$i", deny(GrantDenial.CALL_ACTIVE), decide(i, holder(PeerAudioUse.CALL, 10_000)))
        }
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.CALL, holder(PeerAudioUse.CALL)))
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MANUAL, holder(PeerAudioUse.CALL)))
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL)))
    }

    @Test fun `call veto precedes ping-pong guard`() {
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL, 100)))
    }

    @Test fun `call intent grants over idle media and unknown`() {
        for (u in listOf(PeerAudioUse.IDLE, PeerAudioUse.MEDIA, PeerAudioUse.UNKNOWN)) {
            assertEquals(grant, decide(HandoverIntent.CALL, holder(u, 0)))
        }
    }

    @Test fun `media within guard window is busy`() {
        assertEquals(deny(GrantDenial.BUSY), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 2_999)))
        assertEquals(deny(GrantDenial.BUSY), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.MEDIA, 0)))
    }

    @Test fun `media at exactly guard boundary is granted`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 3_000)))
    }

    @Test fun `media with null takeover time skips guard`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, null)))
    }

    @Test fun `guard constant is three seconds`() {
        assertEquals(3_000L, HandoverGrantPolicy.PING_PONG_GUARD_MS)
    }

    @Test fun `manual ignores guard`() {
        for (u in listOf(PeerAudioUse.IDLE, PeerAudioUse.MEDIA, PeerAudioUse.UNKNOWN)) {
            assertEquals(grant, decide(HandoverIntent.MANUAL, holder(u, 0)))
        }
    }

    @Test fun `media grants idle media and unknown holders after guard`() {
        for (u in listOf(PeerAudioUse.IDLE, PeerAudioUse.MEDIA, PeerAudioUse.UNKNOWN)) {
            assertEquals(grant, decide(HandoverIntent.MEDIA, holder(u, 10_000)))
        }
    }
}
