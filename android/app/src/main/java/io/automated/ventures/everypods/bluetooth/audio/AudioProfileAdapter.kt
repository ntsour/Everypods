package io.automated.ventures.everypods.bluetooth.audio

/**
 * Owns one asynchronous profile-proxy operation. The platform backend is kept behind
 * interfaces so callback ordering, duplicate callbacks and ambiguous failures can be
 * verified without a Bluetooth radio. A thrown invocation is ambiguous: the OS may
 * already have received it, so it must never be treated as a definite rejection.
 */
class AudioProfileAdapter(private val backend: Backend) {
    interface Backend {
        /** False means the proxy request could not be started. */
        fun acquire(callback: (Proxy?) -> Unit): Boolean
    }
    interface Proxy {
        fun state(headset: String): AudioConnectionCoordinator.Profile
        /** null means Android returned no definite Boolean result. */
        fun connect(headset: String): Boolean?
        fun close()
    }
    enum class DispatchResult { NOT_DISPATCHED, ALREADY_CONNECTED, ALREADY_CONNECTING, REJECTED, ACCEPTED, UNKNOWN }

    fun query(headset: String, completed: (AudioConnectionCoordinator.Profile) -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeOnce(profile: AudioConnectionCoordinator.Profile) {
            if (finished.compareAndSet(false, true)) completed(profile)
        }
        val started = try {
            backend.acquire { proxy ->
                if (proxy == null) completeOnce(AudioConnectionCoordinator.Profile.UNKNOWN)
                else try {
                    if (!finished.get()) completeOnce(runCatching { proxy.state(headset) }
                        .getOrDefault(AudioConnectionCoordinator.Profile.UNKNOWN))
                } finally { runCatching { proxy.close() } }
            }
        } catch (_: Exception) { false }
        if (!started) completeOnce(AudioConnectionCoordinator.Profile.UNKNOWN)
    }

    fun connect(headset: String, dispatch: (() -> Unit) -> Unit, completed: (DispatchResult) -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeOnce(result: DispatchResult) {
            if (finished.compareAndSet(false, true)) completed(result)
        }
        val started = try {
            backend.acquire { proxy ->
                if (proxy == null) completeOnce(DispatchResult.NOT_DISPATCHED)
                else try {
                    // Claim this callback before any command. Duplicate callbacks only close.
                    if (finished.compareAndSet(false, true)) {
                        var commandInvoked = false
                        val result = try {
                            val state = proxy.state(headset)
                            if (state == AudioConnectionCoordinator.Profile.CONNECTED) DispatchResult.ALREADY_CONNECTED
                            else if (state == AudioConnectionCoordinator.Profile.CONNECTING) DispatchResult.ALREADY_CONNECTING
                            else if (state != AudioConnectionCoordinator.Profile.DISCONNECTED) DispatchResult.NOT_DISPATCHED
                            else {
                                var platformResult: Boolean? = null
                                // The driver holds its serialization lock while it
                                // validates the current intent and executes this closure.
                                dispatch { commandInvoked = true; platformResult = proxy.connect(headset) }
                                if (!commandInvoked) DispatchResult.NOT_DISPATCHED
                                else when (platformResult) {
                                    true -> DispatchResult.ACCEPTED
                                    false -> DispatchResult.REJECTED
                                    null -> DispatchResult.UNKNOWN
                                }
                            }
                        } catch (_: Exception) {
                            // A query/authorization failure precedes OS dispatch. A thrown
                            // invocation itself remains ambiguous because Binder may have
                            // delivered the command before raising the exception.
                            if (commandInvoked) DispatchResult.UNKNOWN else DispatchResult.NOT_DISPATCHED
                        }
                        completed(result)
                    }
                } finally { runCatching { proxy.close() } }
            }
        } catch (_: Exception) { false }
        if (!started) completeOnce(DispatchResult.NOT_DISPATCHED)
    }
}
