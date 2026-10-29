package io.automated.ventures.everypods.utils

/**
 * Pure timing guard for lid-open auto-connect.
 *
 * A successful local A2DP connection that disappears immediately after a passive
 * lid-open attempt is a contention signal: another phone still owns the AirPods.
 * Retrying in that state only creates an A2DP tug-of-war.
 */
object LidAutoconnectPolicy {
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
