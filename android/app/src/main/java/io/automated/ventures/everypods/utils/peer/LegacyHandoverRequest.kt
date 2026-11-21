package io.automated.ventures.everypods.utils.peer

/**
 * Legacy (pre acquire-v2) handover requests, mapped onto the grant policy.
 * See docs/peer-mesh-spec.md §4.3. A legacy request carries no intent on the wire, so it is
 * inferred from the packet: REQUEST_HANDOVER is the ring/call takeover, REQUEST_DISCONNECT the
 * media takeover.
 */
object LegacyHandoverRequest {
    enum class Kind { HANDOVER, DISCONNECT }

    sealed interface Action {
        /** Release the AirPods to the requester. */
        data object Yield : Action

        /** Keep the AirPods and tell the requester with AIRPODS_CONNECTED. */
        data class ReplyStillHeld(val reason: GrantDenial) : Action
    }

    fun intentFor(kind: Kind): HandoverIntent = when (kind) {
        Kind.HANDOVER -> HandoverIntent.CALL
        Kind.DISCONNECT -> HandoverIntent.MEDIA
    }

    fun actionFor(decision: GrantDecision): Action = when (decision) {
        GrantDecision.Grant -> Action.Yield
        is GrantDecision.Deny -> Action.ReplyStillHeld(decision.reason)
    }

    /** Intent, then policy decision, then action; [decide] is the holder's policy call. */
    fun resolve(kind: Kind, decide: (HandoverIntent) -> GrantDecision): Action =
        actionFor(decide(intentFor(kind)))
}

/** One-time settings migration for the yield ("let the AirPods move when ...") settings. */
object YieldSettingsMigration {
    const val FLAG = "yield_settings_v1_migrated"

    /**
     * Value to write to `takeover_when_call`, or null when no migration is due. The setting was
     * never enforced and was seeded true; enforcing it as stored would let calls be taken over.
     */
    fun callSettingToWrite(alreadyMigrated: Boolean): Boolean? = if (alreadyMigrated) null else false
}
