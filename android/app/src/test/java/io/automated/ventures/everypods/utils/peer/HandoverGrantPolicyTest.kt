package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class HandoverGrantPolicyTest {
    private val grant = GrantDecision.Grant
    private fun deny(r: GrantDenial) = GrantDecision.Deny(r)
    private fun settings(idle: Boolean = true, media: Boolean = true, call: Boolean = false) =
        YieldSettings(idle, media, call)
    private fun holder(use: PeerAudioUse, since: Long? = null, holds: Boolean = true) =
        HolderState(holds, use, since)
    private fun decide(i: HandoverIntent, h: HolderState, s: YieldSettings = settings()) =
        HandoverGrantPolicy.decide(i, h, s)

    // Rule 1
    @Test fun `non-holder grants every intent regardless of audio use and settings`() {
        val none = settings(false, false, false)
        for (i in HandoverIntent.entries) for (u in PeerAudioUse.entries) {
            assertEquals("$i $u", grant, decide(i, holder(u, 0, holds = false), none))
        }
    }

    // Rule 2
    @Test fun `passive is denied HELD for a holder in every audio state`() {
        for (u in PeerAudioUse.entries) {
            assertEquals(deny(GrantDenial.HELD), decide(HandoverIntent.PASSIVE, holder(u), settings(true, true, true)))
        }
    }

    // Rule 3
    @Test fun `call in progress denies media and passive-free intents when whenCall false`() {
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL)))
    }
    @Test fun `call in progress grants media when whenCall true`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL, 10_000), settings(call = true)))
    }
    @Test fun `call intent during call denied when whenCall false`() {
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.CALL, holder(PeerAudioUse.CALL)))
    }
    @Test fun `call intent during call granted when whenCall true`() {
        assertEquals(grant, decide(HandoverIntent.CALL, holder(PeerAudioUse.CALL), settings(call = true)))
    }
    @Test fun `manual during call denied when whenCall false`() {
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MANUAL, holder(PeerAudioUse.CALL)))
    }
    @Test fun `manual during call granted when whenCall true`() {
        assertEquals(grant, decide(HandoverIntent.MANUAL, holder(PeerAudioUse.CALL), settings(call = true)))
    }
    @Test fun `call rule precedes ping-pong guard`() {
        assertEquals(deny(GrantDenial.CALL_ACTIVE), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL, 100)))
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.CALL, 100), settings(call = true)))
    }

    // Rule 4
    @Test fun `call intent grants over idle media and unknown even when yield settings are off`() {
        val off = settings(false, false, false)
        for (u in listOf(PeerAudioUse.IDLE, PeerAudioUse.MEDIA, PeerAudioUse.UNKNOWN)) {
            assertEquals(grant, decide(HandoverIntent.CALL, holder(u, 0), off))
        }
    }

    // Rule 5
    @Test fun `media within guard window is busy`() {
        assertEquals(deny(GrantDenial.BUSY), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 2_999)))
        assertEquals(deny(GrantDenial.BUSY), decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 0)))
    }
    @Test fun `media at exactly guard boundary is not busy`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 3_000)))
    }
    @Test fun `media with null takeover time skips guard`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, null)))
    }
    @Test fun `guard constant is three seconds`() {
        assertEquals(3_000L, HandoverGrantPolicy.PING_PONG_GUARD_MS)
    }
    @Test fun `busy takes precedence over policy denial`() {
        assertEquals(deny(GrantDenial.BUSY),
            decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 1), settings(false, false)))
    }

    // Rule 6
    @Test fun `manual ignores guard and idle media settings`() {
        val off = settings(false, false, false)
        for (u in listOf(PeerAudioUse.IDLE, PeerAudioUse.MEDIA, PeerAudioUse.UNKNOWN)) {
            assertEquals(grant, decide(HandoverIntent.MANUAL, holder(u, 0), off))
        }
    }

    // Rule 7
    @Test fun `idle holder denied by policy when whenIdle false`() {
        assertEquals(deny(GrantDenial.POLICY),
            decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 5_000), settings(idle = false)))
    }
    @Test fun `media holder denied by policy when whenMedia false`() {
        assertEquals(deny(GrantDenial.POLICY),
            decide(HandoverIntent.MEDIA, holder(PeerAudioUse.MEDIA, 5_000), settings(media = false)))
    }
    @Test fun `idle holder ignores whenMedia and media holder ignores whenIdle`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 5_000), settings(idle = true, media = false)))
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.MEDIA, 5_000), settings(idle = false, media = true)))
    }
    @Test fun `unknown audio use needs both idle and media settings`() {
        val u = holder(PeerAudioUse.UNKNOWN, 5_000)
        assertEquals(deny(GrantDenial.POLICY), decide(HandoverIntent.MEDIA, u, settings(idle = false, media = true)))
        assertEquals(deny(GrantDenial.POLICY), decide(HandoverIntent.MEDIA, u, settings(idle = true, media = false)))
        assertEquals(deny(GrantDenial.POLICY), decide(HandoverIntent.MEDIA, u, settings(false, false)))
        assertEquals(grant, decide(HandoverIntent.MEDIA, u, settings(true, true)))
    }

    // Rule 8
    @Test fun `media grants idle and media holders when settings allow`() {
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.IDLE, 10_000)))
        assertEquals(grant, decide(HandoverIntent.MEDIA, holder(PeerAudioUse.MEDIA, 10_000)))
    }
}
