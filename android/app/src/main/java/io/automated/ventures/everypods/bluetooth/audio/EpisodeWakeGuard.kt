package io.automated.ventures.everypods.bluetooth.audio

/** Owns at most one bounded execution lease. Old episode callbacks cannot release a newer lease. */
class EpisodeWakeGuard(private val acquireLease: (Long) -> Lease) {
    fun interface Lease { fun release() }
    private var currentId: Long? = null
    private var currentLease: Lease? = null

    @Synchronized fun acquire(id: Long, timeoutMs: Long): Boolean {
        require(timeoutMs > 0)
        if (currentId == id && currentLease != null) return true
        releaseCurrent()
        return try {
            currentLease = acquireLease(timeoutMs)
            currentId = id
            true
        } catch (_: Exception) {
            currentId = null
            currentLease = null
            false
        }
    }

    @Synchronized fun release(id: Long) {
        if (currentId == id) releaseCurrent()
    }

    /** No pending callback or live coordinator work should keep the CPU awake. */
    @Synchronized fun releaseIfIdle(id: Long, callbackPending: Boolean, coordinatorActive: Boolean) {
        if (currentId == id && !callbackPending && !coordinatorActive) releaseCurrent()
    }

    @Synchronized fun close() = releaseCurrent()

    private fun releaseCurrent() {
        val lease = currentLease
        currentId = null
        currentLease = null
        if (lease != null) runCatching { lease.release() }
    }
}
