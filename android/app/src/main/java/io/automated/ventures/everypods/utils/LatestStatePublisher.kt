package io.automated.ventures.everypods.utils

/** Main-thread/serial-owner latest-state publisher; null represents removal. */
class LatestStatePublisher<T>(
    private val nowMs: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val publish: (T?) -> Unit,
    private val minimumIntervalMs: Long = 750L,
) {
    private var published = false
    private var displayed: T? = null
    private var pending: T? = null
    private var hasPending = false
    private var scheduled = false
    private var stopped = false
    private var lastPublishedAtMs = 0L

    init { require(minimumIntervalMs >= 0L) }

    fun submit(state: T?) {
        if (stopped) return
        pending = state
        hasPending = true
        if (published && state == displayed) {
            clearPending()
            return
        }
        val delay = if (published) (minimumIntervalMs - (nowMs() - lastPublishedAtMs)).coerceAtLeast(0L) else 0L
        if (delay == 0L) {
            cancelScheduled()
            scheduled = false
            emit()
        } else if (!scheduled) {
            scheduled = true
            schedule(delay) {
                scheduled = false
                if (!stopped && hasPending) emit()
            }
        }
    }

    private fun emit() {
        val state = pending
        if (!hasPending || published && state == displayed) { clearPending(); return }
        publish(state)
        displayed = state
        published = true
        lastPublishedAtMs = nowMs()
        hasPending = false
    }

    private fun clearPending() {
        hasPending = false
        scheduled = false
        cancelScheduled()
    }

    fun stop() {
        stopped = true
        clearPending()
    }
}
