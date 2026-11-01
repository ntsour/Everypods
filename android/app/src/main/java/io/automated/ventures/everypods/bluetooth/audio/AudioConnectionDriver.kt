package io.automated.ventures.everypods.bluetooth.audio

/**
 * Effect bridge. An adapter must deliver callbacks from any thread, query actual profile
 * state instead of trusting broadcasts, and return UNKNOWN if it cannot query. This driver
 * serializes all events; it has no Android dependency and is testable with a fake port.
 * A command is fenced a second time immediately before it reaches the port.
 */
class AudioConnectionDriver(
    private val coordinator: AudioConnectionCoordinator,
    private val port: Port,
    // The observed failures took 16–21 seconds; leave room for Android to finish,
    // then stop active work without pretending the command has been released.
    private val commandTimeoutMs: Long = 30_000L,
) {
    init { require(commandTimeoutMs > 0) }
    interface Port {
        fun loadPendingCommand(): AudioCommandFenceStore.Pending? = null
        fun persistPendingCommand(headset: String): AudioCommandFenceStore.Pending? =
            AudioCommandFenceStore.Pending(headset, "volatile", false)
        fun markPendingConnecting(token: String): Boolean = true
        fun resolvePendingCommand(token: String): Boolean = true
        fun evidence(headset: String, intent: AudioConnectionCoordinator.Intent?): AudioConnectionCoordinator.Evidence
        fun query(headset: String, complete: (AudioConnectionCoordinator.Evidence) -> Unit)
        fun coordinate(intent: AudioConnectionCoordinator.Intent, commandNumber: Int)
        // Invoke dispatch with the actual OS command; the driver serializes both.
        fun connect(intentId: Long, headset: String, dispatch: (() -> Boolean, () -> Unit) -> Unit,
                    notDispatched: () -> Unit, rejected: () -> Unit, dispatched: () -> Unit)
        fun scheduleRetry(intentId: Long, callback: () -> Unit) { callback() }
        /** Timers must be bounded, asynchronous and based on the same monotonic clock as evidence. */
        fun scheduleDeadline(delayMs: Long, callback: () -> Unit) {}
        fun scheduleCommandTimeout(delayMs: Long, callback: () -> Unit) = scheduleDeadline(delayMs, callback)
        fun outcome(intentId: Long, result: AudioConnectionCoordinator.Result)
    }

    private var pendingLoaded = false
    private var pendingCommand: AudioCommandFenceStore.Pending? = null
    private var releaseGeneration = 0L
    private var profileQueryGeneration = 0L

    private fun ensurePendingLoaded() {
        if (pendingLoaded) return
        pendingLoaded = true
        pendingCommand = try { port.loadPendingCommand() } catch (_: Exception) {
            AudioCommandFenceStore.Pending("", "", false)
        }
        pendingCommand?.takeIf { it.headset.isNotBlank() && it.token.isNotBlank() }
            ?.let { coordinator.restoreUnresolved(it.headset, it.observedConnecting) }
    }

    /** An unresolved local OS command is not an idle ownership report. */
    @Synchronized fun hasUnresolvedCommand(): Boolean {
        ensurePendingLoaded()
        return pendingCommand != null
    }

    @Synchronized fun reconcilePending() {
        ensurePendingLoaded()
        val snapshot = coordinator.snapshot
        if (snapshot.unresolved) snapshot.intent?.let { dispatch(listOf(AudioConnectionCoordinator.Effect.QueryProfile(it.headset))) }
    }

    private fun resolvePending(token: String?) {
        if (token == null || pendingCommand?.token != token) return
        if (runCatching { port.resolvePendingCommand(token) }.getOrDefault(false)) pendingCommand = null
    }

    @Synchronized fun submit(intent: AudioConnectionCoordinator.Intent) {
        ensurePendingLoaded()
        if (pendingCommand != null && !coordinator.snapshot.unresolved) {
            port.outcome(intent.id, AudioConnectionCoordinator.Result.UNRESOLVED)
            return
        }
        val before = coordinator.snapshot.intent?.id
        val initialEvidence = port.evidence(intent.headset, intent)
        val effects = coordinator.submit(intent, initialEvidence)
        if (coordinator.snapshot.intent?.id != before) {
            releaseGeneration++
            val remaining = (intent.expiresAtMs - initialEvidence.nowMs).coerceAtLeast(0)
            port.scheduleDeadline(remaining) { onDeadline(intent.id) }
        }
        dispatch(effects)
    }

    /** Release invalidates pending connects, but keeps an issued OS command unresolved. */
    @Synchronized fun beginRelease(): Long {
        ensurePendingLoaded()
        releaseGeneration++
        coordinator.snapshot.intent?.let { dispatch(coordinator.cancel(it.id)) }
        return releaseGeneration
    }

    @Synchronized fun invalidateRelease() { releaseGeneration++ }
    @Synchronized fun isReleaseCurrent(generation: Long): Boolean = generation == releaseGeneration
    @Synchronized fun dispatchIfReleaseCurrent(generation: Long, command: () -> Unit) {
        if (generation == releaseGeneration) command()
    }

    /** Fence a delayed secondary-profile callback to the same still-valid intent. */
    @Synchronized fun isIntentCurrent(intentId: Long, headset: String): Boolean {
        val snapshot = coordinator.snapshot
        val intent = snapshot.intent ?: return false
        return intent.id == intentId && intent.headset.equals(headset, ignoreCase = true) &&
            (snapshot.result == null || snapshot.result == AudioConnectionCoordinator.Result.CONNECTED) &&
            port.evidence(headset, intent).nowMs < intent.expiresAtMs
    }

    /** Serializes a secondary-profile command with cancellation and replacement. */
    @Synchronized fun dispatchIfIntentCurrent(intentId: Long, headset: String, command: () -> Unit) {
        if (isIntentCurrent(intentId, headset)) command()
    }

    @Synchronized fun onProfileBroadcast(headset: String, profile: AudioConnectionCoordinator.Profile) {
        ensurePendingLoaded()
        dispatch(coordinator.observedProfile(headset, profile))
    }

    @Synchronized fun onExistingProfile(intentId: Long, profile: AudioConnectionCoordinator.Profile) {
        dispatch(coordinator.existingProfile(intentId, profile))
    }

    @Synchronized fun onCoordinationChanged(intentId: Long) {
        val intent = coordinator.snapshot.intent ?: return
        if (intent.id == intentId) dispatch(coordinator.assess(port.evidence(intent.headset, intent)))
    }

    @Synchronized fun cancel(intentId: Long) { dispatch(coordinator.cancel(intentId)) }
    @Synchronized fun timeout(intentId: Long) { dispatch(coordinator.timeout(intentId)) }

    @Synchronized fun expire(intentId: Long) = onDeadline(intentId)

    @Synchronized private fun onDeadline(intentId: Long) {
        val snapshot = coordinator.snapshot
        val intent = snapshot.intent ?: return
        if (intent.id != intentId || snapshot.result != null) return
        val evidence = port.evidence(intent.headset, intent)
        if (evidence.nowMs < intent.expiresAtMs) {
            port.scheduleDeadline(intent.expiresAtMs - evidence.nowMs) { onDeadline(intentId) }
            return
        }
        if (snapshot.unresolved) dispatch(coordinator.timeout(intentId))
        else dispatch(coordinator.assess(evidence))
    }

    private fun scheduleCommandTimeout(intentId: Long, commandNumber: Int) {
        val snapshot = coordinator.snapshot
        val intent = snapshot.intent ?: return
        if (intent.id != intentId || snapshot.commandIntentId != intentId ||
            snapshot.commands != commandNumber || !snapshot.unresolved || snapshot.result != null) return
        val expiresAt = port.evidence(intent.headset, intent).nowMs + commandTimeoutMs
        fun check() {
            synchronized(this) {
                val current = coordinator.snapshot
                val currentIntent = current.intent ?: return
                if (currentIntent.id != intentId || current.commandIntentId != intentId ||
                    current.commands != commandNumber || !current.unresolved || current.result != null) return
                val remaining = expiresAt - port.evidence(currentIntent.headset, currentIntent).nowMs
                if (remaining > 0) port.scheduleCommandTimeout(remaining, ::check)
                else dispatch(coordinator.timeout(intentId))
            }
        }
        port.scheduleCommandTimeout(commandTimeoutMs, ::check)
    }

    private fun dispatch(effects: List<AudioConnectionCoordinator.Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is AudioConnectionCoordinator.Effect.QueryProfile -> {
                    val requested = coordinator.snapshot
                    val queryGeneration = ++profileQueryGeneration
                    port.query(effect.headset) { evidence ->
                        synchronized(this) {
                            val current = coordinator.snapshot
                            if (queryGeneration == profileQueryGeneration &&
                                current.intent?.id == requested.intent?.id &&
                                current.commandIntentId == requested.commandIntentId &&
                                current.unresolved == requested.unresolved) {
                                val effects = coordinator.reconciledProfile(effect.headset, evidence)
                                val updated = coordinator.snapshot
                                val pending = pendingCommand
                                if (current.unresolved && pending != null) {
                                    if (!updated.unresolved || updated.commandIntentId != current.commandIntentId) {
                                        resolvePending(pending.token)
                                    } else if (!pending.observedConnecting && updated.observedConnecting &&
                                        runCatching { port.markPendingConnecting(pending.token) }.getOrDefault(false)) {
                                        pendingCommand = pending.copy(observedConnecting = true)
                                    }
                                }
                                dispatch(effects)
                            }
                        }
                    }
                }
                is AudioConnectionCoordinator.Effect.Coordinate -> {
                    coordinator.snapshot.intent?.takeIf { it.id == effect.intentId }?.let {
                        port.coordinate(it, effect.commandNumber)
                    }
                }
                is AudioConnectionCoordinator.Effect.Connect -> {
                    val current = port.evidence(effect.headset, coordinator.snapshot.intent)
                    if (!coordinator.mayDispatch(effect.intentId, current)) {
                        dispatch(coordinator.dispatchCancelled(effect.intentId, port.evidence(effect.headset, coordinator.snapshot.intent)))
                    } else {
                        val commandNumber = coordinator.snapshot.commands
                        var operationToken: String? = null
                        var persistenceFailed = false
                        port.connect(effect.intentId, effect.headset, dispatch = { authorize, command ->
                            synchronized(this) {
                                val allowed = coordinator.mayDispatch(effect.intentId, port.evidence(effect.headset, coordinator.snapshot.intent))
                                if (!allowed) dispatch(coordinator.dispatchCancelled(effect.intentId, port.evidence(effect.headset, coordinator.snapshot.intent)))
                                else if (authorize()) {
                                    val pending = if (pendingCommand == null)
                                        runCatching { port.persistPendingCommand(effect.headset) }.getOrNull() else null
                                    if (pending == null) persistenceFailed = true
                                    else {
                                        pendingCommand = pending
                                        operationToken = pending.token
                                        command()
                                    }
                                }
                            }
                        }, notDispatched = {
                            synchronized(this) {
                                resolvePending(operationToken)
                                val reason = if (persistenceFailed) AudioConnectionCoordinator.Result.STORAGE_UNAVAILABLE
                                    else AudioConnectionCoordinator.Result.CANCELLED
                                dispatch(coordinator.dispatchCancelled(effect.intentId, port.evidence(effect.headset, coordinator.snapshot.intent), reason))
                            }
                        }, rejected = {
                            synchronized(this) {
                                resolvePending(operationToken)
                                dispatch(coordinator.commandRejected(effect.intentId, port.evidence(effect.headset, coordinator.snapshot.intent)))
                            }
                        }, dispatched = {
                            synchronized(this) { scheduleCommandTimeout(effect.intentId, commandNumber) }
                        })
                    }
                }
                is AudioConnectionCoordinator.Effect.Retry -> port.scheduleRetry(effect.intentId) {
                    synchronized(this) {
                        val intent = coordinator.snapshot.intent
                        if (intent?.id == effect.intentId && coordinator.snapshot.phase == AudioConnectionCoordinator.Phase.RETRY_WAIT) {
                            dispatch(coordinator.assess(port.evidence(intent.headset, intent)))
                        }
                    }
                }
                is AudioConnectionCoordinator.Effect.Outcome -> port.outcome(effect.intentId, effect.result)
            }
        }
    }
}
