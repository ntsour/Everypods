package io.automated.ventures.everypods.utils

/**
 * Pure timing guard for lid-open auto-connect.
 *
 * A successful local A2DP connection that disappears immediately after a passive
 * lid-open attempt is a contention signal: another phone still owns the AirPods.
 * Retrying in that state only creates an A2DP tug-of-war.
 */
object LidAutoconnectPolicy {
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
