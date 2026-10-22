/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Intent
import android.os.Binder
import kotlinx.coroutines.flow.StateFlow

enum class RelayInterruption { NONE, TEMPORARY, LOCAL_AUDIO }

enum class RelayRole { NONE, SOURCE, RECEIVER }
enum class RelayStage { STOPPED, WAITING_FOR_SOURCE, CONNECTING, VERIFYING, TRUSTED, WAITING_FOR_AUDIO, STREAMING, BUFFERING, SUSPENDED, FAILED }

data class RelayPeerReadiness(
    val mac: String,
    val name: String,
    val online: Boolean,
    val authorized: Boolean,
    val holdsAirPods: Boolean,
    val allowedToSend: Boolean,
)

data class RelayPeerVerification(val stage: RelayStage, val code: String? = null, val reason: String? = null)

data class RelayOutputBufferState(
    val requestedMillis: Int,
    val effectiveMillis: Int,
    val capacityMillis: Int,
    val startThresholdMillis: Int?,
    val underruns: Int,
)

data class RelayUiState(
    val peerMac: String? = null,
    val role: RelayRole = RelayRole.NONE,
    val networkMode: String? = null,
    val stage: RelayStage = RelayStage.STOPPED,
    val verificationCode: String? = null,
    val stablePeerId: String? = null,
    val reason: String? = null,
    val captureApprovalReady: Boolean = false,
    val audioActive: Boolean = false,
    val interruption: RelayInterruption = RelayInterruption.NONE,
    val resumeUnavailable: Boolean = false,
    val suspensionExpiresAtMillis: Long? = null,
    val heartRateBpm: Int? = null,
    val heartRateUpdatedAtMillis: Long? = null,
    val heartRateSharingActive: Boolean = false,
    val heartRateSharingBluetooth: Boolean = false,
    val heartRateSharingTickr: Boolean = false,
    /** A different authorized sender is requesting the receiver while a stream is active. */
    val pendingSenderMac: String? = null,
    val pendingRequestExpiresAtMillis: Long? = null,
    val reservationExpiresAtMillis: Long? = null,
    val waitingForReceiverApproval: Boolean = false,
    val receiverBusy: Boolean = false,
    val peerReadiness: Map<String, RelayPeerReadiness> = emptyMap(),
    val peerVerifications: Map<String, RelayPeerVerification> = emptyMap(),
    val remoteReceiverActive: Boolean = false,
    val remoteActiveSenderName: String? = null,
    /** Receiver-side estimated capture-to-Android-output delay; excludes Bluetooth device buffering. */
    val relayDelayEstimateMillis: Int? = null,
    val relayClockSyncUncertaintyMillis: Int? = null,
    val relayJitterTargetMillis: Int? = null,
    val relayAudioTrackQueueMillis: Int? = null,
    /** Capture timestamp age when the corresponding media reached the receiver. */
    val relayCaptureToReceiverMillis: Int? = null,
    /** Source-side Opus encode and synchronous transport enqueue durations. */
    val relayOutputBuffer: RelayOutputBufferState? = null,
    val relayCaptureDeliveryMillis: Int? = null,
    val relayCaptureReadPeakMillis: Int? = null,
    val relayEncodeMillis: Int? = null,
    val relaySendCallMillis: Int? = null,
)

interface RelaySessionApi {
    val state: StateFlow<RelayUiState>
    fun prepareReceiver(peerMac: String)
    fun pairPeer(peerMac: String)
    fun startSource(peerMac: String)
    fun approvePendingSwitch()
    fun declinePendingSwitch()
    fun confirmPairing(peerMac: String)
    fun cancelPairing(peerMac: String)
    fun isPeerTrusted(peerMac: String): Boolean
    fun forgetPeer(peerMac: String)
    fun startCapture(resultCode: Int, consentData: Intent, muteLocalPlayback: Boolean)
    fun resumeRelay()
    fun stopRelay()
    suspend fun stopRelayAndAwait()
}

/** Pure admission policy for the receiver's single active stream and single pending switch. */
enum class RelayAdmissionDecision { RESERVE_RECEIVER, REQUEST_SWITCH, DUPLICATE_REQUEST, BUSY }

object RelayReceiverAdmission {
    const val SWITCH_REQUEST_TIMEOUT_MS = 30_000L
    const val RESERVATION_TIMEOUT_MS = 60_000L

    fun decide(
        requester: String,
        activeSender: String?,
        active: Boolean,
        reservedSender: String?,
        pendingSender: String?,
    ): RelayAdmissionDecision = when {
        active && activeSender.equals(requester, ignoreCase = true) -> RelayAdmissionDecision.DUPLICATE_REQUEST
        active && pendingSender.equals(requester, ignoreCase = true) -> RelayAdmissionDecision.DUPLICATE_REQUEST
        active && pendingSender != null -> RelayAdmissionDecision.BUSY
        active -> RelayAdmissionDecision.REQUEST_SWITCH
        reservedSender.equals(requester, ignoreCase = true) -> RelayAdmissionDecision.DUPLICATE_REQUEST
        reservedSender != null -> RelayAdmissionDecision.BUSY
        pendingSender != null -> RelayAdmissionDecision.BUSY
        else -> RelayAdmissionDecision.RESERVE_RECEIVER
    }
}

class RelaySessionBinder(private val relay: RelaySessionApi) : Binder() {
    fun relay(): RelaySessionApi = relay
}

/** A saved Wi-Fi connection never suppresses handover by itself. */
object RelayHandoverPolicy {
    fun suppressAutomaticHandover(sourceRequested: Boolean, state: RelayUiState): Boolean =
        sourceRequested && state.role == RelayRole.SOURCE && state.stage in setOf(
            RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED,
        )
}
