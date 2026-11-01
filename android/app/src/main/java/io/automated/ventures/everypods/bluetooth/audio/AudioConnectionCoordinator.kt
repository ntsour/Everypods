package io.automated.ventures.everypods.bluetooth.audio

/**
 * Serialized decision core for one headset. Callers must serialize events and execute
 * returned effects in order. Profile broadcasts are observations, not command IDs.
 * No Android APIs, persisted grants, or remembered owner imply a connected profile.
 */
class AudioConnectionCoordinator(private val maxPassiveCommands: Int = 2) {
    enum class Trigger { PASSIVE, MEDIA, CALL, MANUAL }
    enum class Profile { UNKNOWN, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
    enum class Phase { IDLE, ASSESSING, COORDINATING, CONNECTING, RETRY_WAIT, CONNECTED, RECONCILING, CANCELLED, FAILED }
    enum class Result { CONNECTED, POLICY_DISABLED, STALE_INTENT, PEER_BUSY, BUDS_STORED, BLUETOOTH_UNAVAILABLE, CANCELLED, SUPERSEDED, FAILED_PROFILE, UNRESOLVED, STORAGE_UNAVAILABLE }

    data class Intent(val id: Long, val headset: String, val trigger: Trigger, val expiresAtMs: Long,
                      val episodeId: Long? = null)
    data class Evidence(val nowMs: Long, val enabled: Boolean, val fresh: Boolean,
                        val budsStored: Boolean, val adapterAvailable: Boolean,
                        val peerBusy: Boolean, val requiresGrant: Boolean, val grantValid: Boolean,
                        val profile: Profile,
                        val grantIntentId: Long? = null, val grantCommandNumber: Int? = null)
    data class Snapshot(val phase: Phase = Phase.IDLE, val intent: Intent? = null,
                        val commands: Int = 0, val unresolved: Boolean = false,
                        val observedConnecting: Boolean = false, val commandIntentId: Long? = null,
                        val result: Result? = null)
    sealed interface Effect {
        data class QueryProfile(val headset: String) : Effect
        data class Coordinate(val intentId: Long, val commandNumber: Int) : Effect
        data class Connect(val intentId: Long, val headset: String) : Effect
        data class Retry(val intentId: Long) : Effect
        data class Outcome(val intentId: Long, val result: Result) : Effect
    }

    @Volatile var snapshot = Snapshot()
        private set

    init { require(maxPassiveCommands > 0) }

    private fun priority(trigger: Trigger): Int = when (trigger) {
        Trigger.PASSIVE -> 0
        Trigger.MEDIA -> 1
        Trigger.MANUAL -> 2
        Trigger.CALL -> 3
    }

    /** Restore uncertainty, never the previous attempt's permission or retry budget. */
    @Synchronized fun restoreUnresolved(headset: String, observedConnecting: Boolean): Boolean {
        if (headset.isBlank() || snapshot.intent != null || snapshot.unresolved) return false
        val restored = Intent(Long.MIN_VALUE, headset, Trigger.PASSIVE, 0L)
        snapshot = Snapshot(Phase.RECONCILING, restored, unresolved = true,
            observedConnecting = observedConnecting, commandIntentId = restored.id, result = Result.UNRESOLVED)
        return true
    }

    /** A replacement invalidates the previous intent but cannot erase an OS command. */
    @Synchronized fun submit(intent: Intent, evidence: Evidence): List<Effect> {
        require(intent.headset.isNotBlank())
        val old = snapshot
        val active = old.intent
        if (active?.id == intent.id) return emptyList() // Duplicate delivery is idempotent.
        val oldIsLive = active != null && (old.result == null || old.result == Result.UNRESOLVED) &&
            evidence.nowMs < active.expiresAtMs
        if (oldIsLive && active!!.trigger == Trigger.PASSIVE && intent.trigger == Trigger.PASSIVE &&
            active.episodeId != null && active.episodeId == intent.episodeId &&
            active.headset.equals(intent.headset, ignoreCase = true)) {
            return listOf(Effect.Outcome(intent.id, Result.SUPERSEDED))
        }
        if (oldIsLive && priority(intent.trigger) < priority(active!!.trigger)) {
            return listOf(Effect.Outcome(intent.id, Result.SUPERSEDED))
        }
        val replaced = if (active != null && old.result == null)
            listOf(Effect.Outcome(active.id, Result.SUPERSEDED)) else emptyList()
        if (old.unresolved) {
            if (!old.intent?.headset.equals(intent.headset, ignoreCase = true)) {
                return listOf(Effect.Outcome(intent.id, Result.UNRESOLVED))
            }
            snapshot = Snapshot(Phase.RECONCILING, intent, unresolved = true,
                observedConnecting = old.observedConnecting, commandIntentId = old.commandIntentId)
            return replaced + Effect.QueryProfile(intent.headset)
        }
        snapshot = Snapshot(Phase.ASSESSING, intent)
        return replaced + assess(evidence)
    }

    /** Called with freshly queried state immediately before coordinating or connecting. */
    @Synchronized fun assess(e: Evidence): List<Effect> {
        val intent = snapshot.intent ?: return emptyList()
        if (snapshot.result != null && snapshot.result != Result.UNRESOLVED) return emptyList()
        if (snapshot.unresolved) {
            snapshot = snapshot.copy(phase = Phase.RECONCILING)
            return listOf(Effect.QueryProfile(intent.headset))
        }
        if (!e.adapterAvailable) return finish(Result.BLUETOOTH_UNAVAILABLE)
        if (intent.trigger == Trigger.PASSIVE && !e.enabled) return finish(Result.POLICY_DISABLED)
        if (intent.trigger == Trigger.PASSIVE && e.budsStored) return finish(Result.BUDS_STORED)
        if (e.nowMs >= intent.expiresAtMs || (intent.trigger == Trigger.PASSIVE && !e.fresh)) return finish(Result.STALE_INTENT)
        if (e.profile == Profile.CONNECTED) return finish(Result.CONNECTED, Phase.CONNECTED)
        if (e.profile != Profile.DISCONNECTED) {
            snapshot = snapshot.copy(phase = Phase.RECONCILING)
            return listOf(Effect.QueryProfile(intent.headset))
        }
        if (e.peerBusy) return finish(Result.PEER_BUSY)
        if (e.requiresGrant && !(e.grantValid && e.grantIntentId == intent.id &&
                e.grantCommandNumber == snapshot.commands + 1)) {
            snapshot = snapshot.copy(phase = Phase.COORDINATING)
            return listOf(Effect.Coordinate(intent.id, snapshot.commands + 1))
        }
        val limit = if (intent.trigger == Trigger.PASSIVE) maxPassiveCommands else 2
        if (snapshot.commands >= limit) return finish(Result.FAILED_PROFILE)
        snapshot = snapshot.copy(phase = Phase.CONNECTING, commands = snapshot.commands + 1, unresolved = true, observedConnecting = false, commandIntentId = intent.id)
        return listOf(Effect.Connect(intent.id, intent.headset))
    }

    /** The adapter must invoke this just before dispatch, with newly read policy and grant. */
    @Synchronized fun mayDispatch(intentId: Long, e: Evidence): Boolean {
        val intent = snapshot.intent ?: return false
        return snapshot.unresolved && snapshot.commandIntentId == intentId && intent.id == intentId &&
            snapshot.result == null && e.adapterAvailable && e.profile == Profile.DISCONNECTED &&
            e.nowMs < intent.expiresAtMs && !e.peerBusy && (!e.requiresGrant ||
                (e.grantValid && e.grantIntentId == intent.id && e.grantCommandNumber == snapshot.commands)) &&
            (intent.trigger != Trigger.PASSIVE || (e.enabled && e.fresh && !e.budsStored))
    }

    /** Dispatch was fenced before reaching the OS, so no command remains outstanding. */
    @Synchronized fun dispatchCancelled(intentId: Long, evidence: Evidence,
                                        result: Result = Result.CANCELLED): List<Effect> {
        if (snapshot.commandIntentId != intentId || !snapshot.unresolved) return emptyList()
        snapshot = snapshot.copy(unresolved = false, commandIntentId = null)
        if (snapshot.intent?.id != intentId) return assess(evidence)
        return finish(result, if (result == Result.CANCELLED) Phase.CANCELLED else Phase.FAILED)
    }

    /** The adapter observed another OS attempt before this command was dispatched. */
    @Synchronized fun existingProfile(intentId: Long, profile: Profile): List<Effect> {
        val intent = snapshot.intent ?: return emptyList()
        if (!snapshot.unresolved || snapshot.commandIntentId != intentId || intent.id != intentId) return emptyList()
        if (profile != Profile.CONNECTING && profile != Profile.CONNECTED) return emptyList()
        // The command budget counts actual dispatches, not a request fenced by an
        // already connecting profile. Continue to track the external attempt.
        snapshot = snapshot.copy(phase = Phase.RECONCILING,
            commands = (snapshot.commands - 1).coerceAtLeast(0),
            unresolved = profile == Profile.CONNECTING,
            observedConnecting = profile == Profile.CONNECTING,
            commandIntentId = if (profile == Profile.CONNECTING) intentId else null)
        return listOf(Effect.QueryProfile(intent.headset))
    }

    /** Broadcasts are untagged and may be stale: reconcile through a fresh query. */
    @Synchronized fun observedProfile(headset: String, profile: Profile): List<Effect> {
        val intent = snapshot.intent ?: return emptyList()
        if (!intent.headset.equals(headset, ignoreCase = true)) return emptyList()
        return listOf(Effect.QueryProfile(intent.headset))
    }

    /** Only a successful current-adapter query is authoritative. */
    @Synchronized fun reconciledProfile(headset: String, evidence: Evidence): List<Effect> {
        val intent = snapshot.intent ?: return emptyList()
        if (!intent.headset.equals(headset, ignoreCase = true)) return emptyList()
        when (evidence.profile) {
            Profile.CONNECTED -> {
                snapshot = snapshot.copy(unresolved = false)
                return finish(Result.CONNECTED, Phase.CONNECTED)
            }
            Profile.CONNECTING -> {
                if (snapshot.unresolved) snapshot = snapshot.copy(observedConnecting = true, phase = Phase.RECONCILING)
                return emptyList()
            }
            Profile.DISCONNECTED -> {
                // A query directly after dispatch may still show the previous state.
                if (snapshot.unresolved && !snapshot.observedConnecting) return emptyList()
                val wasUnresolved = snapshot.unresolved
                val wasCurrentCommand = snapshot.commandIntentId == intent.id
                snapshot = snapshot.copy(unresolved = false, commandIntentId = null)
                if (snapshot.phase == Phase.CANCELLED || snapshot.phase == Phase.FAILED) return emptyList()
                // An unresolved timeout ends this episode. A later definite failure
                // releases the command guard, but must not revive the expired work.
                if (snapshot.result == Result.UNRESOLVED) {
                    snapshot = snapshot.copy(phase = Phase.FAILED)
                    return emptyList()
                }
                if (wasUnresolved && wasCurrentCommand) {
                    snapshot = snapshot.copy(phase = Phase.RETRY_WAIT)
                    return listOf(Effect.Retry(intent.id))
                }
                return assess(evidence)
            }
            else -> return emptyList()
        }
    }

    /** A synchronous false return means the OS rejected this exact command. */
    @Synchronized fun commandRejected(intentId: Long, evidence: Evidence): List<Effect> {
        if (snapshot.commandIntentId != intentId || !snapshot.unresolved) return emptyList()
        snapshot = snapshot.copy(unresolved = false, phase = Phase.RETRY_WAIT, commandIntentId = null)
        if (snapshot.result != null && snapshot.result != Result.UNRESOLVED) return emptyList()
        return if (snapshot.intent?.id == intentId) listOf(Effect.Retry(intentId)) else assess(evidence)
    }

    /** Timeout is not a release: the next attempt must first reconcile the profile. */
    @Synchronized fun timeout(intentId: Long): List<Effect> {
        if (snapshot.intent?.id != intentId || !snapshot.unresolved || snapshot.result != null) return emptyList()
        snapshot = snapshot.copy(phase = Phase.RECONCILING, result = Result.UNRESOLVED)
        return listOf(Effect.Outcome(intentId, Result.UNRESOLVED), Effect.QueryProfile(snapshot.intent!!.headset))
    }

    @Synchronized fun cancel(intentId: Long): List<Effect> {
        if (snapshot.intent?.id != intentId) return emptyList()
        return finish(Result.CANCELLED, Phase.CANCELLED)
    }

    private fun finish(result: Result, phase: Phase = Phase.FAILED): List<Effect> {
        val intent = snapshot.intent ?: return emptyList()
        if (snapshot.result != null && snapshot.result != Result.UNRESOLVED) return emptyList()
        snapshot = snapshot.copy(phase = phase, result = result)
        return listOf(Effect.Outcome(intent.id, result))
    }
}
