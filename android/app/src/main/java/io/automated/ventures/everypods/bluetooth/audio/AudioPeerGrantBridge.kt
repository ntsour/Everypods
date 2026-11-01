package io.automated.ventures.everypods.bluetooth.audio

import io.automated.ventures.everypods.utils.peer.PassiveAcquisitionCoordinator

/**
 * Binds the audio intent/command number to the existing distributed reservation.
 * The backend owns wire transport, peer/session checks and in-flight command proof.
 * All calls are serialized; late readiness callbacks never become a different grant.
 */
class AudioPeerGrantBridge(private val backend: Backend) {
    data class Request(val requestId: String, val headsetId: String)
    data class Binding(val intentId: Long, val commandNumber: Int, val request: Request,
                       val ready: PassiveAcquisitionCoordinator.Event.ReadyToConnect? = null)
    interface Backend {
        /** Begin or find a pending request; return its exact wire request and headset IDs. */
        fun begin(): Request?
        fun isCurrent(request: Request): Boolean
        fun authorize(ready: PassiveAcquisitionCoordinator.Event.ReadyToConnect, issueCommand: Boolean): Boolean
        /** Must cancel only the matching request, never a newer reservation. */
        fun cancel(request: Request)
    }
    private var current: Binding? = null

    @Synchronized fun begin(intentId: Long, commandNumber: Int): Boolean {
        require(commandNumber > 0)
        val existing = current
        if (existing != null && existing.intentId == intentId && existing.commandNumber == commandNumber &&
            backend.isCurrent(existing.request)) return true
        if (existing != null) backend.cancel(existing.request)
        current = null
        val request = backend.begin() ?: return false
        current = Binding(intentId, commandNumber, request)
        return true
    }

    /** Only an exact request/headset pair can bind a received peer grant. */
    @Synchronized fun ready(event: PassiveAcquisitionCoordinator.Event.ReadyToConnect): Binding? {
        val binding = current ?: return null
        if (binding.request.requestId != event.requestId || binding.request.headsetId != event.headsetId) return null
        if (!backend.authorize(event, issueCommand = false)) return null
        return binding.copy(ready = event).also { current = it }
    }

    @Synchronized fun readyFor(intentId: Long, commandNumber: Int): PassiveAcquisitionCoordinator.Event.ReadyToConnect? {
        val binding = current ?: return null
        return if (binding.intentId == intentId && binding.commandNumber == commandNumber) binding.ready else null
    }

    @Synchronized fun isValid(intentId: Long, commandNumber: Int): Boolean {
        val binding = current ?: return false
        val ready = binding.ready ?: return false
        return binding.intentId == intentId && binding.commandNumber == commandNumber &&
            backend.authorize(ready, issueCommand = false)
    }

    /** Revalidates and commits the existing protocol's grant at the OS command boundary. */
    @Synchronized fun commit(intentId: Long, commandNumber: Int): Boolean {
        val binding = current ?: return false
        val ready = binding.ready ?: return false
        if (binding.intentId != intentId || binding.commandNumber != commandNumber) return false
        return backend.authorize(ready, issueCommand = true)
    }

    @Synchronized fun cancel(intentId: Long) {
        val binding = current ?: return
        if (binding.intentId != intentId) return
        current = null
        backend.cancel(binding.request)
    }
}
