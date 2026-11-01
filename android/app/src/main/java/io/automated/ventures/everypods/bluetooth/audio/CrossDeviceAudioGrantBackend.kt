package io.automated.ventures.everypods.bluetooth.audio

import io.automated.ventures.everypods.utils.CrossDevice
import io.automated.ventures.everypods.utils.peer.PassiveAcquisitionCoordinator

/** Adapter for the existing authenticated peer protocol; policy is re-read at each call. */
class CrossDeviceAudioGrantBackend(
    private val lastOwnerEligible: () -> Boolean,
    private val enabled: () -> Boolean,
    private val lidOpen: () -> Boolean,
) : AudioPeerGrantBridge.Backend {
    override fun begin(): AudioPeerGrantBridge.Request? {
        if (!CrossDevice.beginPassiveAcquisition(lastOwnerEligible(), enabled(), lidOpen())) return null
        val (requestId, headsetId) = CrossDevice.pendingPassiveRequest() ?: return null
        return AudioPeerGrantBridge.Request(requestId, headsetId)
    }

    override fun isCurrent(request: AudioPeerGrantBridge.Request): Boolean =
        CrossDevice.pendingPassiveRequest() == (request.requestId to request.headsetId)

    override fun authorize(ready: PassiveAcquisitionCoordinator.Event.ReadyToConnect, issueCommand: Boolean): Boolean =
        CrossDevice.authorizePassiveAcquisition(ready, lidOpen(), enabled(), issueCommand)

    override fun cancel(request: AudioPeerGrantBridge.Request) {
        CrossDevice.cancelPassiveRequest(request.requestId, request.headsetId)
    }
}
