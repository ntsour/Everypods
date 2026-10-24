/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

/** Monotonic-time policy for one requested sensor session. The service owns scheduling. */
class HeartRateContinuity(private val elapsed: () -> Long) {
    private var outageDeadline: Long? = null
    private var awaitingSince: Long? = null
    private var retryCount = 0

    val recovering: Boolean get() = outageDeadline != null
    val deadline: Long? get() = outageDeadline

    fun beginOutage() {
        if (outageDeadline == null) {
            outageDeadline = elapsed() + RECOVERY_WINDOW_MS
            retryCount = 0
        }
        awaitingSince = null
    }

    fun commandAccepted() { awaitingSince = elapsed() }

    /** A successful write does not establish recovery; only a fresh sample can. */
    fun waitingForSample(): Boolean = awaitingSince?.let { elapsed() - it < NO_DATA_MS } == true

    fun expired(): Boolean = outageDeadline?.let { elapsed() >= it } == true

    fun nextDelay(): Long {
        val delay = (2_000L shl retryCount.coerceAtMost(2)).coerceAtMost(8_000L)
        retryCount++
        return delay
    }

    fun sampleAccepted() {
        outageDeadline = null
        awaitingSince = null
        retryCount = 0
    }

    fun clear() = sampleAccepted()

    companion object {
        const val FRESH_MS = 5_000L
        const val NO_DATA_MS = 15_000L
        const val RECOVERY_WINDOW_MS = 60_000L

        fun fresh(arrivedAt: Long, now: Long): Boolean =
            arrivedAt <= now && now - arrivedAt < FRESH_MS
    }
}

/** One consecutive BLE transport failure episode; receiver disconnects do not count. */
class HeartRateTransportContinuity(private val elapsed: () -> Long) {
    private var outageDeadline: Long? = null
    private var readySince: Long? = null
    private var failures = 0

    val recovering: Boolean get() = outageDeadline != null && readySince == null
    fun beginOutage() {
        if (outageDeadline == null) outageDeadline = elapsed() + 60_000L
        readySince = null
    }
    fun expired(): Boolean = outageDeadline?.let { elapsed() >= it } == true
    fun exhausted(): Boolean = failures > 3 || expired()
    fun nextFailureDelay(): Long {
        beginOutage()
        failures++
        return (2_000L shl (failures - 1).coerceAtMost(2)).coerceAtMost(8_000L)
    }
    fun ready() { if (readySince == null) readySince = elapsed() }
    fun stable() {
        if (readySince?.let { elapsed() - it >= 30_000L } == true) clear()
    }
    fun clear() {
        outageDeadline = null
        readySince = null
        failures = 0
    }
}

/** Memory-only monitor intent, including the fixed background grace deadline. */
class HeartRateMonitorWindow(private val elapsed: () -> Long) {
    var requested = false
        private set
    var suspended = false
        private set
    var target: String? = null
        private set
    var deadline: Long? = null
        private set

    fun start(device: String?) {
        requested = true
        suspended = false
        target = device
        deadline = null
    }

    fun suspend(): Boolean {
        if (!requested || suspended) return false
        suspended = true
        deadline = elapsed() + 60_000L
        return true
    }

    fun canResume(device: String?): Boolean = requested && suspended &&
        target != null && target == device && deadline?.let { elapsed() < it } == true

    fun expired(): Boolean = deadline?.let { elapsed() >= it } == true

    fun resume() {
        suspended = false
        deadline = null
    }

    fun clear() {
        requested = false
        suspended = false
        target = null
        deadline = null
    }
}
