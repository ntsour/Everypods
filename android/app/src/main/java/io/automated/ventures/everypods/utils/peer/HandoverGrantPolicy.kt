package io.automated.ventures.everypods.utils.peer

/** Which kinds of audio activity the holder is willing to give up to a requesting peer. */
data class YieldSettings(val whenIdle: Boolean, val whenMedia: Boolean, val whenCall: Boolean)

/** The local device's view of its own AirPods hold when a peer asks for them. */
data class HolderState(val holds: Boolean, val audioUse: PeerAudioUse, val msSinceOwnTakeover: Long?)

enum class GrantDenial { HELD, CALL_ACTIVE, BUSY, POLICY }

sealed interface GrantDecision {
    data object Grant : GrantDecision
    data class Deny(val reason: GrantDenial) : GrantDecision
}

object HandoverGrantPolicy {
    const val PING_PONG_GUARD_MS = 3_000L

    /**
     * Decides whether the holder lets a peer with [intent] take the AirPods. Rules, in order:
     * 1. A non-holder always grants.
     * 2. PASSIVE never takes from a holder (HELD).
     * 3. During a call, every intent (including CALL and MANUAL) follows `whenCall` (else CALL_ACTIVE).
     * 4. CALL grants.
     * 5. MEDIA within [PING_PONG_GUARD_MS] of our own takeover is denied (BUSY).
     * 6. MANUAL grants, overriding the idle/media settings.
     * 7. IDLE/MEDIA/UNKNOWN audio is denied (POLICY) unless the matching yield setting allows it;
     *    UNKNOWN needs both `whenIdle` and `whenMedia`.
     * 8. Otherwise grant.
     *
     * See docs/peer-mesh-spec.md §4.3. Rule 3 deliberately resolves the spec's "call vs call"
     * case through the "on a call" setting.
     */
    fun decide(intent: HandoverIntent, holder: HolderState, settings: YieldSettings): GrantDecision {
        if (!holder.holds) return GrantDecision.Grant
        if (intent == HandoverIntent.PASSIVE) return GrantDecision.Deny(GrantDenial.HELD)
        if (holder.audioUse == PeerAudioUse.CALL) {
            return if (settings.whenCall) GrantDecision.Grant else GrantDecision.Deny(GrantDenial.CALL_ACTIVE)
        }
        if (intent == HandoverIntent.CALL) return GrantDecision.Grant
        val since = holder.msSinceOwnTakeover
        if (intent == HandoverIntent.MEDIA && since != null && since < PING_PONG_GUARD_MS) {
            return GrantDecision.Deny(GrantDenial.BUSY)
        }
        if (intent == HandoverIntent.MANUAL) return GrantDecision.Grant
        val allowed = when (holder.audioUse) {
            PeerAudioUse.IDLE -> settings.whenIdle
            PeerAudioUse.MEDIA -> settings.whenMedia
            PeerAudioUse.UNKNOWN -> settings.whenIdle && settings.whenMedia
            PeerAudioUse.CALL -> true
        }
        return if (allowed) GrantDecision.Grant else GrantDecision.Deny(GrantDenial.POLICY)
    }
}
