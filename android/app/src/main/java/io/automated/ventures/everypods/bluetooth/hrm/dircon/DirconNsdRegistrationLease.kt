package io.automated.ventures.everypods.bluetooth.hrm.dircon

/** Tracks one asynchronous NSD registration and owns exactly one eventual unregister. */
internal class DirconNsdRegistrationLease(
    private val unregister: () -> Unit,
    private val onRegistered: () -> Unit,
    private val onRegistrationFailed: (Int) -> Unit,
) {
    private enum class Phase { REGISTERING, REGISTERED, TERMINAL }

    private val lock = Any()
    private var phase = Phase.REGISTERING
    private var unregisterIssued = false

    fun registered() {
        val action = synchronized(lock) {
            when (phase) {
                Phase.REGISTERING -> {
                    phase = Phase.REGISTERED
                    1
                }
                Phase.REGISTERED -> 0 // Duplicate callback for the same registration.
                Phase.TERMINAL -> if (markUnregisterLocked()) 2 else 0 // Stop/failure won the race.
            }
        }
        when (action) {
            1 -> onRegistered()
            2 -> unregister()
        }
    }

    fun registrationFailed(errorCode: Int) {
        val notify = synchronized(lock) {
            if (phase != Phase.REGISTERING) false
            else {
                phase = Phase.TERMINAL
                true
            }
        }
        if (notify) onRegistrationFailed(errorCode)
    }

    /** If registration is pending, a late success callback performs cleanup. */
    fun stop() {
        val shouldUnregister = synchronized(lock) {
            when (phase) {
                Phase.REGISTERING -> {
                    phase = Phase.TERMINAL
                    false
                }
                Phase.REGISTERED -> {
                    phase = Phase.TERMINAL
                    markUnregisterLocked()
                }
                Phase.TERMINAL -> false
            }
        }
        if (shouldUnregister) unregister()
    }

    private fun markUnregisterLocked(): Boolean {
        if (unregisterIssued) return false
        unregisterIssued = true
        return true
    }
}
