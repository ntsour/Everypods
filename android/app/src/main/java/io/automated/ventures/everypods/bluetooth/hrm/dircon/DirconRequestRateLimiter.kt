package io.automated.ventures.everypods.bluetooth.hrm.dircon

/** Per-client token bucket. The caller supplies monotonic elapsed milliseconds. */
internal class DirconRequestRateLimiter(
    private val capacity: Double = DEFAULT_CAPACITY,
    requestsPerSecond: Double = DEFAULT_REQUESTS_PER_SECOND,
    nowElapsedMs: Long,
) {
    private val refillPerMs = requestsPerSecond / 1_000.0
    private var tokens = capacity
    private var updatedAtElapsedMs = nowElapsedMs

    init {
        require(capacity > 0.0)
        require(requestsPerSecond > 0.0)
        require(nowElapsedMs >= 0L)
    }

    fun tryAcquire(nowElapsedMs: Long): Boolean {
        require(nowElapsedMs >= updatedAtElapsedMs) { "elapsed_clock_moved_backwards" }
        tokens = (tokens + (nowElapsedMs - updatedAtElapsedMs) * refillPerMs).coerceAtMost(capacity)
        updatedAtElapsedMs = nowElapsedMs
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 200.0
        const val DEFAULT_REQUESTS_PER_SECOND = 100.0
    }
}
