package io.automated.ventures.everypods.bluetooth.audio

/** Limits explicit snapshot refreshes within one bounded passive intent. */
class PeerRefreshBudget(private val intervalMs: Long = 2_000L, private val maximum: Int = 5) {
    init { require(intervalMs > 0 && maximum > 0) }
    private var intentId: Long? = null
    private var count = 0
    private var lastAt = 0L

    @Synchronized fun take(intent: Long, nowMs: Long): Boolean {
        if (intentId != intent) {
            intentId = intent
            count = 0
            lastAt = 0L
        }
        if (count >= maximum || nowMs < 0 || (count > 0 && (nowMs < lastAt || nowMs - lastAt < intervalMs))) return false
        count++
        lastAt = nowMs
        return true
    }
}
