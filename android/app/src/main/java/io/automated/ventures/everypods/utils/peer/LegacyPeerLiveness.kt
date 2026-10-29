package io.automated.ventures.everypods.utils.peer

/** Socket-local watchdog. A previous connection or another protocol cannot refresh it. */
class LegacyPeerLiveness(private val connectedAtMs: Long) {
    private var lastResponseAtMs = connectedAtMs
    @Synchronized fun onStatusResponse(nowMs: Long) {
        if (nowMs >= lastResponseAtMs) lastResponseAtMs = nowMs
    }
    @Synchronized fun responseAgeMs(nowMs: Long): Long = (nowMs - lastResponseAtMs).coerceAtLeast(0L)
    @Synchronized fun expired(nowMs: Long): Boolean = responseAgeMs(nowMs) >= RESPONSE_TIMEOUT_MS
    companion object { const val RESPONSE_TIMEOUT_MS = 45_000L }
}
