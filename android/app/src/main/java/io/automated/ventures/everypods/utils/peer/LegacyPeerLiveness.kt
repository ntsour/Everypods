package io.automated.ventures.everypods.utils.peer

/**
 * Socket-local watchdog for the legacy status keep-alive. A previous connection or another protocol cannot refresh it.
 *
 * Each keep-alive round asks [staleBeforeRequest] before it sends the next status request. The link is stale only when
 * an earlier request is still unanswered after [MAX_UNANSWERED_ROUNDS] rounds and at least [RESPONSE_TIMEOUT_MS].
 * It never expires on elapsed time alone: the keep-alive's own wait (a coroutine delay) stops while the device
 * sleeps, while elapsedRealtime keeps counting, so the first round after a long sleep used to find "no reply for
 * 45 s" and close a healthy link a few milliseconds before the reply arrived (seen 128 times in one night on a
 * Pixel and a Xiaomi tablet, each 45-48 s after the link came up).
 */
class LegacyPeerLiveness(connectedAtMs: Long) {
    private var lastResponseAtMs = connectedAtMs
    private var outstandingSinceMs: Long? = null
    private var unansweredRounds = 0

    /** Any AirPods state packet from the peer proves its app is alive. */
    @Synchronized fun onStatusResponse(nowMs: Long) {
        if (nowMs >= lastResponseAtMs) lastResponseAtMs = nowMs
        outstandingSinceMs = null
        unansweredRounds = 0
    }

    /** One keep-alive round, before sending. True when the link is stale and must be closed. */
    @Synchronized fun staleBeforeRequest(nowMs: Long): Boolean {
        val since = outstandingSinceMs ?: return false
        unansweredRounds++
        return unansweredRounds >= MAX_UNANSWERED_ROUNDS && nowMs - since >= RESPONSE_TIMEOUT_MS
    }

    /** A status request went out. The oldest unanswered request sets the clock. */
    @Synchronized fun onRequestSent(nowMs: Long) {
        if (outstandingSinceMs == null) outstandingSinceMs = nowMs
    }

    @Synchronized fun responseAgeMs(nowMs: Long): Long = (nowMs - lastResponseAtMs).coerceAtLeast(0L)

    companion object {
        const val RESPONSE_TIMEOUT_MS = 45_000L
        const val MAX_UNANSWERED_ROUNDS = 2
    }
}
