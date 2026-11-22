package io.automated.ventures.everypods.utils.peer

/**
 * Pure decision logic for [PeerGattClient] (no Android types, so it is unit-tested).
 * All times are milliseconds on one monotonic clock chosen by the caller.
 */
object PeerGattClientPolicy {
    const val CONNECT_TIMEOUT_MS = 25_000L
    const val SETTLE_AFTER_CONNECT_MS = 200L
    const val STAGE_TIMEOUT_MS = 10_000L
    const val DISCONNECT_WAIT_MS = 2_000L
    const val CLOSE_COOLDOWN_MS = 500L
    const val BACKOFF_BASE_MS = 2_000L
    const val BACKOFF_MAX_MS = 60_000L
    const val PING_INTERVAL_MS = 10_000L
    const val SILENCE_LIMIT_MS = 35_000L
    const val KEEPALIVE_MS = 20_000L

    /** Nordic CONNECTION_TIMEOUT_THRESHOLD: a 133/147 this late is the link timing out. */
    const val TIMEOUT_THRESHOLD_MS = 20_000L
    const val GATT_ERROR = 133
    const val GATT_CONN_TIMEOUT_147 = 147

    enum class FailureKind { TIMEOUT, STACK_ERROR, OTHER }

    /** Delay before the redial that follows the [failures]-th consecutive failure (1-based). */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(16)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** 133/147 after [TIMEOUT_THRESHOLD_MS] is a timeout, earlier a stack error; both back off. */
    fun classify(status: Int, elapsedMs: Long): FailureKind = when (status) {
        GATT_ERROR, GATT_CONN_TIMEOUT_147 ->
            if (elapsedMs >= TIMEOUT_THRESHOLD_MS) FailureKind.TIMEOUT else FailureKind.STACK_ERROR
        else -> FailureKind.OTHER
    }

    fun isSilent(lastHeardMs: Long, nowMs: Long): Boolean = nowMs - lastHeardMs > SILENCE_LIMIT_MS
    fun pingDue(lastPingMs: Long, nowMs: Long): Boolean = nowMs - lastPingMs >= PING_INTERVAL_MS
    fun keepAliveDue(lastKeepAliveMs: Long, nowMs: Long): Boolean = nowMs - lastKeepAliveMs >= KEEPALIVE_MS

    /** Per-address redial bookkeeping: backoff, hold-off and the post-close cooldown. */
    class DialSchedule {
        private val failures = HashMap<String, Int>()
        private val backoffUntil = HashMap<String, Long>()
        private val holdUntil = HashMap<String, Long>()
        private val closeUntil = HashMap<String, Long>()

        fun canDial(address: String, nowMs: Long): Boolean = nowMs >= notBefore(address)

        fun notBefore(address: String): Long =
            maxOf(backoffUntil[address] ?: 0L, holdUntil[address] ?: 0L, closeUntil[address] ?: 0L)

        /** Records a failed attempt or lost link; returns the backoff applied. */
        fun recordFailure(address: String, nowMs: Long): Long {
            val n = (failures[address] ?: 0) + 1
            failures[address] = n
            val delay = backoffMs(n)
            backoffUntil[address] = nowMs + delay
            return delay
        }

        fun failureCount(address: String): Int = failures[address] ?: 0

        /** The link reached ready: backoff starts over (a hold-off stays). */
        fun recordReady(address: String) {
            failures.remove(address)
            backoffUntil.remove(address)
        }

        /** Never redial [address] before now + [holdOffMs]; never shortens an existing hold. */
        fun holdOff(address: String, nowMs: Long, holdOffMs: Long) {
            if (holdOffMs <= 0) return
            holdUntil[address] = maxOf(holdUntil[address] ?: 0L, nowMs + holdOffMs)
        }

        /** A GATT client was closed: give the stack [CLOSE_COOLDOWN_MS] before the next connect. */
        fun noteClosed(address: String, nowMs: Long) {
            closeUntil[address] = nowMs + CLOSE_COOLDOWN_MS
        }

        fun forget(address: String) {
            failures.remove(address); backoffUntil.remove(address)
            holdUntil.remove(address); closeUntil.remove(address)
        }

        fun reset() {
            failures.clear(); backoffUntil.clear(); holdUntil.clear(); closeUntil.clear()
        }
    }

    /** Link ids: each connect attempt gets a new one; callbacks of an older link are stale. */
    class LinkIds {
        private var counter = 0L
        private val current = HashMap<String, Long>()

        fun next(address: String): Long {
            val id = ++counter
            current[address] = id
            return id
        }

        fun isCurrent(address: String, id: Long): Boolean = current[address] == id

        fun release(address: String, id: Long) {
            if (current[address] == id) current.remove(address)
        }

        fun reset() = current.clear()
    }
}
