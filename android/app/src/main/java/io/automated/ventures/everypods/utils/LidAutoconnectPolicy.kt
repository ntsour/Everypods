package io.automated.ventures.everypods.utils

/**
 * Pure timing guard for lid-open auto-connect.
 *
 * A successful local A2DP connection that disappears immediately after a passive
 * lid-open attempt is a contention signal: another phone still owns the AirPods.
 * Retrying in that state only creates an A2DP tug-of-war.
 */
object LidAutoconnectPolicy {
    const val BLE_EVIDENCE_MAX_AGE_MS = 10_000L

    /** Every scanner start gets a full grace period, even if its predecessor saw an old ad. */
    fun isScanSilent(nowMs: Long, startedAtMs: Long, lastAdvertisementMs: Long, timeoutMs: Long): Boolean {
        if (startedAtMs <= 0L) return true
        val lastActivity = maxOf(startedAtMs, lastAdvertisementMs)
        return nowMs >= lastActivity && nowMs - lastActivity >= timeoutMs
    }

    /** Scan timestamps use Android elapsed realtime, not wall time or callback delivery time. */
    fun isFreshBleObservation(observedAtMs: Long, nowMs: Long): Boolean =
        observedAtMs > 0 && nowMs >= observedAtMs && nowMs - observedAtMs <= BLE_EVIDENCE_MAX_AGE_MS

    /** Last-holder recovery may start from BLE alone, but only for fresh verified availability. */
    fun mayStartFromBleAvailability(
        observedAtMs: Long, nowMs: Long, identityVerified: Boolean, lidOpen: Boolean,
        leftInEar: Boolean, rightInEar: Boolean, stored: Boolean,
        featureEnabled: Boolean, lastHolder: Boolean, alreadyConnected: Boolean,
    ): Boolean = isFreshBleObservation(observedAtMs, nowMs) && identityVerified &&
        (lidOpen || leftInEar || rightInEar) && !stored && featureEnabled && lastHolder && !alreadyConnected

    fun mayAcceptBleScan(observedAtMs: Long, nowMs: Long, latestAcceptedMs: Long?): Boolean =
        isFreshBleObservation(observedAtMs, nowMs) &&
            (latestAcceptedMs == null || observedAtMs >= latestAcceptedMs)

    /** Contradictory wear reports and stale charging flags are not proof of storage. */
    fun bleConfirmsStored(observedAtMs: Long, nowMs: Long, lidOpen: Boolean,
                          leftCharging: Boolean, rightCharging: Boolean,
                          leftInEar: Boolean, rightInEar: Boolean, batteryLevelsValid: Boolean): Boolean =
        isFreshBleObservation(observedAtMs, nowMs) && batteryLevelsValid && !lidOpen && leftCharging && rightCharging &&
            !leftInEar && !rightInEar

    /** An open lid can intentionally start a connection while buds are still charging. */
    fun storedBudsCancel(confirmedAacpStored: Boolean, freshBleStored: Boolean,
                         freshBleLidOpen: Boolean, freshAacpRemoval: Boolean): Boolean =
        !freshBleLidOpen && (confirmedAacpStored || (freshBleStored && !freshAacpRemoval))

    /** A fresh closed-empty observation can preserve, but cannot extend, recent removal evidence. */
    fun bleCanKeepRemovalIntent(recentEvidenceMs: Long, currentObservationMs: Long,
                                nowMs: Long, stored: Boolean): Boolean =
        !stored && isFreshBleObservation(recentEvidenceMs, nowMs) &&
            isFreshBleObservation(currentObservationMs, nowMs)

    /** A terminal or skipped attempt still consumes the episode until it closes or expires. */
    fun isDuplicateActiveEpisode(nowMs: Long, deadlineMs: Long): Boolean =
        deadlineMs > 0L && nowMs < deadlineMs

    /** Closing an empty case after removing the buds must not cancel the connect. */
    fun preserveRecoveryAfterClose(nowMs: Long, deadlineMs: Long, bothBudsCharging: Boolean): Boolean =
        nowMs < deadlineMs && !bothBudsCharging

    /** A callback from a cancelled/replaced cycle must never issue a headset command. */
    fun mayIssueDeferredConnect(
        nowMs: Long, requestedDeadlineMs: Long, activeDeadlineMs: Long, active: Boolean,
        enabled: Boolean, peerHolding: Boolean, peerCooldownUntilMs: Long, contentionUntilMs: Long,
    ): Boolean = active && enabled && !peerHolding && requestedDeadlineMs == activeDeadlineMs &&
        nowMs < activeDeadlineMs && nowMs >= peerCooldownUntilMs && nowMs >= contentionUntilMs

    const val CONTESTED_CONNECTION_WINDOW_MS = 8_000L
    const val CONTENTION_COOLDOWN_MS = 15_000L

    fun isContestedConnection(
        lidAttemptAtMs: Long,
        a2dpConnectedAtMs: Long,
        a2dpDisconnectedAtMs: Long,
    ): Boolean =
        lidAttemptAtMs > 0L &&
            a2dpConnectedAtMs >= lidAttemptAtMs &&
            a2dpDisconnectedAtMs >= a2dpConnectedAtMs &&
            a2dpDisconnectedAtMs - a2dpConnectedAtMs < CONTESTED_CONNECTION_WINDOW_MS
}
