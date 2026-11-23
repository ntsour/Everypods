package io.automated.ventures.everypods.utils.peer

/** The local device's view of its own AirPods hold when a peer asks for them. */
data class HolderState(val holds: Boolean, val audioUse: PeerAudioUse, val msSinceOwnTakeover: Long?)

enum class GrantDenial { HELD, CALL_ACTIVE, BUSY }

sealed interface GrantDecision {
    data object Grant : GrantDecision
    data class Deny(val reason: GrantDenial) : GrantDecision
}

object HandoverGrantPolicy {
    const val PING_PONG_GUARD_MS = 3_000L

    /**
     * Decides whether the holder lets a peer with [intent] take the AirPods. The reference is
     * Android's existing behaviour (call veto always on) plus the ping-pong guard. Rules, in order:
     * 1. A non-holder always grants.
     * 2. PASSIVE never takes from a holder (HELD).
     * 3. During a call, every intent is denied (CALL_ACTIVE); there is no setting.
     * 4. CALL grants.
     * 5. MEDIA within [PING_PONG_GUARD_MS] of our own takeover is denied (BUSY).
     * 6. Otherwise grant.
     *
     * See docs/peer-mesh-spec.md §4.3.
     */
    fun decide(intent: HandoverIntent, holder: HolderState): GrantDecision {
        if (!holder.holds) return GrantDecision.Grant
        if (intent == HandoverIntent.PASSIVE) return GrantDecision.Deny(GrantDenial.HELD)
        if (holder.audioUse == PeerAudioUse.CALL) return GrantDecision.Deny(GrantDenial.CALL_ACTIVE)
        if (intent == HandoverIntent.CALL) return GrantDecision.Grant
        val since = holder.msSinceOwnTakeover
        if (intent == HandoverIntent.MEDIA && since != null && since < PING_PONG_GUARD_MS) {
            return GrantDecision.Deny(GrantDenial.BUSY)
        }
        return GrantDecision.Grant
    }
}
