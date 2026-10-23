/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.service.quicksettings.TileService
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.relay.security.RelayPeerSessionController
import io.automated.ventures.everypods.relay.transport.RelaySignalCodec
import io.automated.ventures.everypods.relay.transport.WebRtcRelayTransport
import io.automated.ventures.everypods.relay.transport.RelayNetworkPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.utils.CrossDevice
import kotlinx.coroutines.delay
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateStatus
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import kotlinx.coroutines.flow.collect
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.RelayStage
import io.automated.ventures.everypods.relay.RelayUiState
import io.automated.ventures.everypods.relay.RelaySessionApi
import io.automated.ventures.everypods.relay.RelaySessionBinder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns the relay control plane and keeps session state independent of the Compose screen. */
class RelayPreviewService : Service(), RelaySessionApi {
    private val binder = RelaySessionBinder(this)
    private val connections by lazy { DeviceConnectionStore(this) }
    private val revocationAttempts = mutableMapOf<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RelayUiState())
    override val state: StateFlow<RelayUiState> = mutableState.asStateFlow()
    private val transports = ConcurrentHashMap<String, WebRtcRelayTransport>()
    private val transportJobs = ConcurrentHashMap<String, Job>()
    private var controller: RelayPeerSessionController? = null
    @Volatile private var audioCapture: RelayAudioCapture? = null
    @Volatile private var audioPlayback: RelayAudioPlayback? = null
    private val playbackStarting = AtomicBoolean(false)
    private val playbackGeneration = AtomicLong(0L)
    private val sentAudioPackets = AtomicLong(0L)
    private val receivedAudioPackets = AtomicLong(0L)
    @Volatile private var stopTimeout: Job? = null
    private var heartRateForwardingJob: Job? = null
    private var heartRateSharingForwardingJob: Job? = null
    private var clockSyncJob: Job? = null
    private val clockSyncIds = AtomicLong(0L)
    private val pendingClockSync = ConcurrentHashMap<Long, Long>()
    @Volatile private var sourceClockOffsetNanos: Long? = null
    private var bestClockSyncRttNanos = Long.MAX_VALUE
    @Volatile private var foregroundStarted = false
    private var sourceSessionRequested = false
    @Volatile private var activeTuning = RelayAudioTuning.DEFAULT
    private var selectedNetworkPath: RelayNetworkPath? = null
    private var networkNegotiating = false
    private var networkPolicy = "auto"
    private var triedHotspotFallback = false
    private var networkSetupTimeout: Job? = null
    private data class PendingSwitch(val mac: String, val request: WebRtcRelayTransport.Signal.RequestPath)
    @Volatile private var pendingSwitch: PendingSwitch? = null
    private var pendingSwitchTimeout: Job? = null
    private var reservationTimeout: Job? = null
    private var suspensionTimeout: Job? = null
    private var terminalStopReason: String? = null
    private var lastBroadcastStatus: String? = null
    private val statusAttemptedPeers = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        connections.migrate(CrossDevice.configuredPeers)
        DeviceConnectionStore.emitting = false
        scope.launch {
            state.collect {
                DeviceConnectionStore.emitting = RelayHandoverPolicy.suppressAutomaticHandover(sourceSessionRequested, it)
                val active = it.role != RelayRole.NONE && it.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
                getSharedPreferences("settings", MODE_PRIVATE).edit {
                    putBoolean("audio_relay_local_active", active)
                    putBoolean("audio_relay_local_suspended", it.stage == RelayStage.SUSPENDED)
                }
                runCatching {
                    TileService.requestListeningState(
                        this@RelayPreviewService,
                        android.content.ComponentName(this@RelayPreviewService, RelayQuickSettingsService::class.java),
                    )
                }
                if (foregroundStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                broadcastPeerSessionStatus(it)
            }
        }
        controller = RelayPeerSessionController(this, object : RelayPeerSessionController.Listener {
            override fun onState(mac: String, state: RelayPeerSessionController.PeerState) {
                scope.launch {
                    if (state.stage == RelayPeerSessionController.Stage.VERIFYING) {
                        ensurePeerConfigured(mac)
                        if (!connections.authorized(mac)) connections.add(mac, DeviceConnectionType.WIFI_RELAY)
                        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    }
                    Log.i(TAG, "Peer signaling state: ${state.stage}${state.reason?.let { ": $it" } ?: ""}")
                    val stage = when (state.stage) {
                        RelayPeerSessionController.Stage.CONNECTING,
                        RelayPeerSessionController.Stage.WAITING_FOR_PEER -> RelayStage.CONNECTING
                        RelayPeerSessionController.Stage.VERIFYING -> RelayStage.VERIFYING
                        RelayPeerSessionController.Stage.CONNECTED -> if (mutableState.value.role == RelayRole.RECEIVER) RelayStage.WAITING_FOR_AUDIO else RelayStage.CONNECTING
                        RelayPeerSessionController.Stage.TRUSTED -> RelayStage.TRUSTED
                        RelayPeerSessionController.Stage.FAILED -> RelayStage.FAILED
                        RelayPeerSessionController.Stage.DISCONNECTED,
                        RelayPeerSessionController.Stage.LISTENING -> RelayStage.STOPPED
                    }
                    mutableState.value = mutableState.value.copy(peerVerifications = mutableState.value.peerVerifications +
                        (mac.uppercase() to RelayPeerVerification(if (stage in setOf(RelayStage.VERIFYING, RelayStage.TRUSTED, RelayStage.FAILED)) stage else RelayStage.STOPPED, state.verificationCode, state.reason)))
                    if (mac.uppercase() in connections.pendingRevocations()) return@launch
                    if (mutableState.value.peerMac != mac && mutableState.value.role != RelayRole.NONE) return@launch
                    // Coordination-link updates are not media-playback state changes.
                    if (transports[mac] != null && mutableState.value.stage == RelayStage.SUSPENDED &&
                        stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.TRUSTED)) return@launch
                    mutableState.value = mutableState.value.copy(
                        peerMac = mac,
                        // A trusted coordination link can connect before AirPods ownership
                        // arrives. It is readiness, not an audio session; otherwise Home
                        // sees a phantom Connecting session and disables every Start button.
                        stage = if (!sourceSessionRequested && !networkNegotiating && transports[mac] == null &&
                            isPeerTrusted(mac) && stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO)) {
                            if (mutableState.value.role == RelayRole.RECEIVER) RelayStage.WAITING_FOR_SOURCE else RelayStage.STOPPED
                        } else stage,
                        verificationCode = state.verificationCode,
                        stablePeerId = state.stableId,
                        reason = state.reason,
                    )
                }
            }

            override fun onAuthenticatedMessage(mac: String, payload: ByteArray) {
                scope.launch {
                    runCatching { RelaySignalCodec.decode(payload) }
                        .onSuccess {
                            Log.d(TAG, "Received authenticated WebRTC signal: ${it.javaClass.simpleName}")
                            runCatching { receiveNetworkSignal(mac, it) }
                                .onFailure { error ->
                                    Log.w(TAG, "Selected relay network setup failed", error)
                                    runCatching { sendNetworkSignal(mac, WebRtcRelayTransport.Signal.PathUnavailable) }
                                    val waitingForRouteFallback = mutableState.value.role == RelayRole.SOURCE && mutableState.value.peerMac == mac && networkPolicy == "auto" && transports[mac] == null
                                    if (waitingForRouteFallback) {
                                        Log.i(TAG, "No matching sender interface; receiver may offer an available device hotspot")
                                    } else if (mutableState.value.peerMac == mac || mutableState.value.role == RelayRole.NONE) {
                                        fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = false)
                                    }
                                }
                        }
                        .onFailure {
                            if (mutableState.value.peerMac == mac || mutableState.value.role == RelayRole.NONE) {
                                fail(mac, it.message ?: getString(R.string.audio_relay_status_heading), notifyPeer = true)
                            } else sendNetworkSignal(mac, WebRtcRelayTransport.Signal.PathUnavailable)
                        }
                }
            }

            override fun onPeerTrusted(mac: String, peerId: String) {
                scope.launch {
                    connections.add(mac, DeviceConnectionType.WIFI_RELAY, verified = true)
                    ensurePeerConfigured(mac)
                    CrossDevice.init(this@RelayPreviewService)
                    if (mutableState.value.peerMac == mac && transports[mac] == null) {
                        mutableState.value = mutableState.value.copy(stage = RelayStage.TRUSTED, stablePeerId = peerId, reason = null)
                    }
                }
            }

            override fun onSecurityReady(mac: String, peerId: String, signalingInitiator: Boolean) {
                scope.launch {
                    if (mac.uppercase() in connections.pendingRevocations()) {
                        controller?.sendAuthenticated(mac, RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelay))
                        return@launch
                    }
                    if (!connections.authorized(mac)) return@launch
                    if (mutableState.value.role == RelayRole.RECEIVER && ownershipRole(mac) == RelayRole.RECEIVER) {
                        val current = mutableState.value
                        if (mac != current.peerMac && mac != current.pendingSenderMac) sendPeerSessionStatus(mac, current)
                        return@launch
                    }
                    if (mutableState.value.peerMac != mac || mutableState.value.role == RelayRole.NONE) return@launch
                    if (mutableState.value.role == RelayRole.SOURCE && !sourceSessionRequested) return@launch
                    if (ownershipRole(mac) != mutableState.value.role) {
                        fail(mac, getString(R.string.audio_relay_ownership_changed), notifyPeer = false)
                        return@launch
                    }
                    if (networkNegotiating || transports.containsKey(mac)) return@launch
                    if (mutableState.value.role == RelayRole.SOURCE) {
                        networkNegotiating = true
                        armNetworkSetupTimeout(mac)
                        networkPolicy = getSharedPreferences("settings", MODE_PRIVATE)
                            .getString("audio_relay_network_${mac.uppercase()}", "auto") ?: "auto"
                        activeTuning = RelayAudioTuning.load(getSharedPreferences("settings", MODE_PRIVATE), mac)
                        Log.i(TAG, "Relay audio profile selected: $activeTuning")
                        triedHotspotFallback = false
                        mutableState.value = mutableState.value.copy(networkMode = networkPolicy)
                        sendNetworkSignal(mac, WebRtcRelayTransport.Signal.RequestPath(networkPolicy, activeTuning))
                    }
                }
            }

            override fun onFailure(mac: String?, reason: String) {
                scope.launch {
                    if (mac == null || mutableState.value.peerMac == mac) {
                        stopLocalAudio()
                        mutableState.value = mutableState.value.copy(stage = RelayStage.FAILED, reason = reason)
                    }
                }
            }
        }).also(RelayPeerSessionController::startListening)
        // Service-owned: ownership changes must stop capture even with no screen bound.
        scope.launch {
            while (true) {
                delay(500)
                connections.pendingRevocations().forEach { address ->
                    if (CrossDevice.isConnectedTo(address) && android.os.SystemClock.elapsedRealtime() - (revocationAttempts[address] ?: -5_000L) >= 5_000L) {
                        revocationAttempts[address] = android.os.SystemClock.elapsedRealtime()
                        deliverRevocation(address)
                    }
                }
                var current = mutableState.value
                val readiness = peerReadinessSnapshot()
                if (current.peerReadiness != readiness) {
                    current = current.copy(peerReadiness = readiness)
                    mutableState.value = current
                }
                val mac = current.peerMac ?: continue
                if (transports.containsKey(mac) && selectedNetworkPath?.available(this@RelayPreviewService) == false) {
                    fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = true)
                    closeTransport(mac)
                    continue
                }
                val role = ownershipRole(mac)
                if (current.role != RelayRole.NONE && current.role != role &&
                    current.stage in setOf(RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)) {
                    Log.i(TAG, "AirPods ownership changed: ${current.role} -> $role; stopping relay")
                    pendingSwitch?.let { runCatching { sendNetworkSignal(it.mac, WebRtcRelayTransport.Signal.PathUnavailable) } }
                    pendingSwitch = null
                    pendingSwitchTimeout?.cancel()
                    pendingSwitchTimeout = null
                    mutableState.value = current.copy(captureApprovalReady = false, audioActive = false)
                    stopRelayAndAwait()
                    mutableState.value = RelayUiState(peerMac = mac, role = ownershipRole(mac),
                        reason = getString(R.string.audio_relay_ownership_changed))
                } else if (current.stage in setOf(RelayStage.STOPPED, RelayStage.WAITING_FOR_SOURCE, RelayStage.TRUSTED, RelayStage.FAILED) && current.role != role) {
                    mutableState.value = current.copy(role = role)
                }
            }
        }
    }

    private fun ownershipRole(mac: String): RelayRole {
        val service = ServiceManager.getService()
        return when {
            service?.isConnected() == true || service?.isA2dpConnected() == true -> RelayRole.RECEIVER
            CrossDevice.isConnectedTo(mac) && CrossDevice.holders.contains(mac) -> RelayRole.SOURCE
            else -> RelayRole.NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun peerReadinessSnapshot(): Map<String, RelayPeerReadiness> {
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        return CrossDevice.configuredPeers.associate { mac ->
            val normalized = mac.uppercase()
            val name = runCatching { adapter?.getRemoteDevice(mac)?.name }.getOrNull()
                ?.takeIf(String::isNotBlank) ?: getString(R.string.audio_relay_device_fallback)
            normalized to RelayPeerReadiness(
                mac = normalized,
                name = name,
                online = CrossDevice.isConnectedTo(mac),
                authorized = isPeerTrusted(mac),
                holdsAirPods = mac in CrossDevice.holders,
                allowedToSend = preferences.getBoolean("audio_relay_allow_$normalized", true),
            )
        }
    }

    private fun broadcastPeerSessionStatus(state: RelayUiState) {
        if (state.role != RelayRole.RECEIVER) return
        val active = state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
        val senderName = if (active) deviceName(state.peerMac) else ""
        val marker = "${state.peerMac}:$active:$senderName:${state.pendingSenderMac}"
        if (marker != lastBroadcastStatus) {
            lastBroadcastStatus = marker
            statusAttemptedPeers.clear()
        }
        CrossDevice.configuredPeers.forEach { mac ->
            if (mac.equals(state.peerMac, true) || mac.equals(state.pendingSenderMac, true) || !isPeerTrusted(mac)) return@forEach
            val normalized = mac.uppercase()
            if (controller?.sendAuthenticated(mac, RelaySignalCodec.encode(WebRtcRelayTransport.Signal.PeerSessionStatus(active, senderName))) == true) {
                statusAttemptedPeers += normalized
            } else if (statusAttemptedPeers.add(normalized)) {
                runCatching { controller?.connectForSession(mac) }
            }
        }
    }

    private fun sendPeerSessionStatus(mac: String, state: RelayUiState) {
        val active = state.role == RelayRole.RECEIVER && state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
        val senderName = if (active) deviceName(state.peerMac) else ""
        runCatching {
            controller?.sendAuthenticated(mac, RelaySignalCodec.encode(WebRtcRelayTransport.Signal.PeerSessionStatus(active, senderName)))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        val audioType = when {
            audioCapture != null -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            audioPlayback != null -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or audioType)
        foregroundStarted = true
        if (intent?.action == ACTION_STOP) {
            stopRelay()
            scope.launch {
                kotlinx.coroutines.delay(STOP_ACK_TIMEOUT_MS)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) resumeRelay()
        if (intent?.action == ACTION_APPROVE_SWITCH) approvePendingSwitch()
        if (intent?.action == ACTION_DECLINE_SWITCH) declinePendingSwitch()
        if (intent?.action == ACTION_FORGET_PEER) {
            intent.getStringExtra(EXTRA_PEER_MAC)?.let { forgetPeer(it) }
        }
        controller?.startListening()
        return START_NOT_STICKY
    }

    override fun prepareReceiver(peerMac: String) {
        if (ownershipRole(peerMac) != RelayRole.RECEIVER) return
        if (mutableState.value.stage in setOf(RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.TRUSTED, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)) return
        mutableState.value = RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.WAITING_FOR_SOURCE)
        controller?.startListening()
    }

    override fun pairPeer(peerMac: String) {
        if (controller?.state(peerMac)?.stage == RelayPeerSessionController.Stage.VERIFYING) return
        connections.add(peerMac, DeviceConnectionType.WIFI_RELAY)
        val current = mutableState.value
        if (current.role != RelayRole.NONE && current.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)) {
            mutableState.value = current.copy(peerVerifications = current.peerVerifications + (peerMac.uppercase() to RelayPeerVerification(RelayStage.CONNECTING)))
        } else mutableState.value = RelayUiState(peerMac = peerMac, role = RelayRole.NONE, stage = RelayStage.CONNECTING)
        controller?.connectForPairing(peerMac)
    }

    override fun startSource(peerMac: String) {
        if (!isPeerTrusted(peerMac)) {
            fail(peerMac, getString(R.string.audio_relay_peer_not_authorized))
            return
        }
        if (ownershipRole(peerMac) != RelayRole.SOURCE) {
            fail(peerMac, getString(R.string.audio_relay_connect_airpods), notifyPeer = false)
            return
        }
        stopLocalAudio()
        terminalStopReason = null
        transports.keys.toList().forEach(::closeTransport)
        sourceSessionRequested = true
        activeTuning = RelayAudioTuning.load(getSharedPreferences("settings", MODE_PRIVATE), peerMac)
        Log.i(TAG, "Relay audio profile selected for next session: $activeTuning")
        DeviceConnectionStore.emitting = true
        reservationTimeout?.cancel()
        reservationTimeout = scope.launch {
            delay(RESERVATION_TIMEOUT_MS)
            if (mutableState.value.role == RelayRole.SOURCE && mutableState.value.peerMac == peerMac &&
                mutableState.value.stage in setOf(RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO)) {
                fail(peerMac, getString(R.string.audio_relay_network_unavailable), notifyPeer = true)
            }
        }
        mutableState.value = RelayUiState(peerMac = peerMac, role = RelayRole.SOURCE, stage = RelayStage.CONNECTING)
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        runCatching { controller?.connectForSession(peerMac) }
            .onFailure { fail(peerMac, it.message ?: "Unable to connect to the paired device") }
    }

    override fun approvePendingSwitch() {
        val request = pendingSwitch ?: return
        if (mutableState.value.role != RelayRole.RECEIVER || ownershipRole(request.mac) != RelayRole.RECEIVER) {
            declinePendingSwitch()
            return
        }
        pendingSwitch = null
        pendingSwitchTimeout?.cancel()
        pendingSwitchTimeout = null
        scope.launch {
            // The current source is stopped before the receiver admits the new one.
            stopRelayAndAwait()
            if (ownershipRole(request.mac) != RelayRole.RECEIVER) return@launch
            mutableState.value = RelayUiState(
                peerMac = request.mac,
                role = RelayRole.RECEIVER,
                stage = RelayStage.CONNECTING,
                networkMode = request.request.mode,
                reservationExpiresAtMillis = System.currentTimeMillis() + RESERVATION_TIMEOUT_MS,
            )
            reservationTimeout?.cancel()
            reservationTimeout = scope.launch {
                delay(RESERVATION_TIMEOUT_MS)
                if (mutableState.value.peerMac == request.mac && mutableState.value.stage != RelayStage.STREAMING) {
                    stopRelay()
                    mutableState.value = RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.WAITING_FOR_SOURCE)
                }
            }
            runCatching { acceptPathRequest(request.mac, request.request) }
                .onFailure { fail(request.mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = true) }
        }
    }

    override fun declinePendingSwitch() {
        val request = pendingSwitch ?: return
        pendingSwitch = null
        pendingSwitchTimeout?.cancel()
        pendingSwitchTimeout = null
        runCatching { sendNetworkSignal(request.mac, WebRtcRelayTransport.Signal.PathUnavailable) }
        mutableState.value = mutableState.value.copy(pendingSenderMac = null, pendingRequestExpiresAtMillis = null)
    }

    override fun confirmPairing(peerMac: String) = controller?.confirmPairing(peerMac) ?: Unit

    override fun cancelPairing(peerMac: String) = controller?.cancelPairing(peerMac) ?: Unit

    override fun isPeerTrusted(peerMac: String): Boolean = connections.authorized(peerMac) && controller?.isTrusted(peerMac) == true

    override fun forgetPeer(peerMac: String) {
        if (pendingSwitch?.mac.equals(peerMac, ignoreCase = true)) declinePendingSwitch()
        if (mutableState.value.peerMac.equals(peerMac, ignoreCase = true)) stopRelay()
        connections.remove(peerMac, DeviceConnectionType.WIFI_RELAY)
        deliverRevocation(peerMac)
    }

    private fun deliverRevocation(mac: String) {
        if (controller?.sendAuthenticated(mac, RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelay)) != true) {
            runCatching { controller?.connectForSession(mac) }
        }
    }

    private fun ensurePeerConfigured(mac: String) {
        val peers = CrossDevice.configuredPeers.map { it.uppercase() }.toSet() + mac.uppercase()
        if (peers != CrossDevice.configuredPeers) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("cross_device_peers", org.json.JSONArray(peers.toList()).toString()).apply()
            CrossDevice.configuredPeers = peers
        }
    }

    override fun startCapture(resultCode: Int, consentData: Intent, muteLocalPlayback: Boolean) {
        val state = mutableState.value
        check(sourceSessionRequested && state.role == RelayRole.SOURCE && state.captureApprovalReady) { getString(R.string.audio_relay_connect_airpods) }
        check(audioCapture == null) { "Audio capture is already active" }
        val mac = checkNotNull(state.peerMac)
        check(ownershipRole(mac) == RelayRole.SOURCE) { getString(R.string.audio_relay_ownership_changed) }
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val tuning = activeTuning
        val captureGeneration = playbackGeneration.get()
        sentAudioPackets.set(0L)
        val capture = RelayAudioCapture(
            context = this,
            tuning = tuning,
            onEncodedFrame = { packet ->
                val sent = captureGeneration == playbackGeneration.get() && transports[mac]?.sendMedia(packet) == true
                val count = sentAudioPackets.incrementAndGet()
                if (count == 1L || count % AUDIO_PACKET_DIAGNOSTIC_INTERVAL == 0L) {
                    Log.i(TAG, "Source media packets=$count sent=$sent")
                }
                sent
            },
            onFrameTiming = { encodeMs, sendMs, captureAgeMs, peakReadMs ->
                scope.launch {
                    if (captureGeneration == playbackGeneration.get()) {
                        mutableState.value = mutableState.value.copy(relayEncodeMillis = encodeMs, relaySendCallMillis = sendMs, relayCaptureDeliveryMillis = captureAgeMs, relayCaptureReadPeakMillis = peakReadMs)
                    }
                }
            },
            muteLocalPlayback = muteLocalPlayback,
            onAudioActivity = { active ->
                scope.launch {
                    if (captureGeneration != playbackGeneration.get()) return@launch
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        audioActive = active,
                        stage = when {
                            current.interruption != RelayInterruption.NONE -> RelayStage.SUSPENDED
                            active -> RelayStage.STREAMING
                            current.stage == RelayStage.STREAMING -> RelayStage.WAITING_FOR_AUDIO
                            else -> current.stage
                        },
                    )
                    sendControl(mac, RelaySessionControl.activity(active))
                }
            },
            onFailure = { reason -> scope.launch {
                if (captureGeneration == playbackGeneration.get()) fail(mac, reason, notifyPeer = true)
            } },
        )
        try {
            capture.start(resultCode, consentData)
            audioCapture = capture
            Log.i(TAG, "Source playback capture started; relay transport available=${transports[mac] != null}")
            mutableState.value = mutableState.value.copy(stage = if (mutableState.value.interruption == RelayInterruption.NONE) RelayStage.WAITING_FOR_AUDIO else RelayStage.SUSPENDED, reason = null, captureApprovalReady = false)
            sendControl(mac, RelaySessionControl.command(RelaySessionControl.CAPTURE_STARTED))
        } catch (error: Exception) {
            capture.close()
            promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            fail(mac, error.message ?: "Playback capture could not start", notifyPeer = true)
            throw error
        }
    }

    override fun resumeRelay() {
        val current = mutableState.value
        val mac = current.peerMac ?: return
        if (current.stage != RelayStage.SUSPENDED || stopTimeout != null) return
        if (current.role == RelayRole.RECEIVER) audioPlayback?.resume()
        else if (current.role == RelayRole.SOURCE) sendControl(mac, RelaySessionControl.command(RelaySessionControl.RESUME_REQUEST))
    }

    override fun stopRelay() = stopRelayWithReason()

    private fun stopRelayWithReason(reason: String? = null) {
        terminalStopReason = reason
        val state = mutableState.value
        val mac = state.peerMac
        // Release local ownership before any network send or acknowledgement wait.
        stopLocalAudio()
        pendingSwitch?.let { runCatching { sendNetworkSignal(it.mac, WebRtcRelayTransport.Signal.PathUnavailable) } }
        if (state.role == RelayRole.SOURCE && state.waitingForReceiverApproval && mac != null) {
            runCatching { sendNetworkSignal(mac, WebRtcRelayTransport.Signal.CancelRequest) }
        }
        pendingSwitchTimeout?.cancel()
        pendingSwitchTimeout = null
        pendingSwitch = null
        if (mac == null) {
            transports.values.forEach(WebRtcRelayTransport::close)
            transports.clear()
            transportJobs.values.forEach(Job::cancel)
            transportJobs.clear()
            mutableState.value = if (ownershipRoleForLocal() == RelayRole.RECEIVER) {
                RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.WAITING_FOR_SOURCE)
            } else RelayUiState()
            return
        }
        sendControl(mac, RelaySessionControl.command(if (reason == null) RelaySessionControl.STOP else RelaySessionControl.SUSPENSION_EXPIRED))
        stopTimeout?.cancel()
        stopTimeout = scope.launch {
            kotlinx.coroutines.delay(STOP_ACK_TIMEOUT_MS)
            closeTransport(mac)
            mutableState.value = RelayUiState(peerMac = mac, role = ownershipRole(mac), stage = RelayStage.STOPPED, reason = terminalStopReason)
            promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    override suspend fun stopRelayAndAwait() {
        stopRelay()
        withTimeoutOrNull(STOP_ACK_TIMEOUT_MS + 500L) {
            state.first { it.stage == RelayStage.STOPPED || it.stage == RelayStage.FAILED }
        }
    }

    private fun stopLocalAudio() {
        playbackGeneration.incrementAndGet()
        suspensionTimeout?.cancel()
        suspensionTimeout = null
        DeviceConnectionStore.emitting = false
        io.automated.ventures.everypods.utils.MediaController.setRelayAudioPlaybackActive(false)
        sourceSessionRequested = false
        Log.i(TAG, "Stopping local relay audio; automatic handover suppression released")
        networkSetupTimeout?.cancel()
        networkSetupTimeout = null
        networkNegotiating = false
        reservationTimeout?.cancel()
        reservationTimeout = null
        selectedNetworkPath = null
        heartRateForwardingJob?.cancel()
        heartRateForwardingJob = null
        heartRateSharingForwardingJob?.cancel()
        heartRateSharingForwardingJob = null
        clockSyncJob?.cancel()
        clockSyncJob = null
        pendingClockSync.clear()
        sourceClockOffsetNanos = null
        bestClockSyncRttNanos = Long.MAX_VALUE
        val capture = audioCapture
        audioCapture = null
        val playback = audioPlayback
        audioPlayback = null
        runCatching { capture?.close() }.onFailure { Log.w(TAG, "Capture cleanup failed", it) }
        runCatching { playback?.close() }.onFailure { Log.w(TAG, "Playback cleanup failed", it) }
    }

    private fun forwardHeartRate(mac: String) {
        heartRateForwardingJob?.cancel()
        val hrState = ServiceManager.getService()?.heartRateState ?: return
        heartRateForwardingJob = scope.launch {
            var lastSampleTime: Long? = null
            hrState.collect { sample ->
                if (mutableState.value.role != RelayRole.RECEIVER || mutableState.value.peerMac != mac) return@collect
                if (sample.status == HeartRateStatus.LIVE && sample.bpm in 1..255 && sample.sampleTimeMillis != lastSampleTime) {
                    lastSampleTime = sample.sampleTimeMillis
                    sendControl(mac, RelaySessionControl.heartRate(sample.bpm!!))
                } else if (sample.status != HeartRateStatus.LIVE) {
                    sendControl(mac, RelaySessionControl.command(RelaySessionControl.HEART_RATE_UNAVAILABLE))
                }
            }
        }
        val sharingState = ServiceManager.getService()?.heartRateSharingState ?: return
        heartRateSharingForwardingJob = scope.launch {
            sharingState.collect { sharing ->
                if (mutableState.value.role == RelayRole.RECEIVER && mutableState.value.peerMac == mac) {
                    sendControl(mac, RelaySessionControl.heartRateSharing(
                        active = sharing.active,
                        bluetooth = sharing.bluetoothPeripheral,
                        tickr = sharing.wahooDiagnostic,
                    ))
                }
            }
        }
    }

    private fun closeTransport(mac: String) {
        stopTimeout?.cancel()
        stopTimeout = null
        transportJobs.remove(mac)?.cancel()
        transports.remove(mac)?.close()
    }

    private fun stopRemoteRelay(mac: String, reason: String? = null) {
        terminalStopReason = reason
        val role = mutableState.value.role
        stopLocalAudio()
        sendControl(mac, RelaySessionControl.command(RelaySessionControl.STOP_ACK))
        stopTimeout?.cancel()
        stopTimeout = scope.launch {
            kotlinx.coroutines.delay(200)
            closeTransport(mac)
            // A remote Stop ends capture on a source; it must not turn that
            // device into a receiver and hide the action to start again.
            mutableState.value = RelayUiState(
                peerMac = mac,
                role = role,
                stage = if (role == RelayRole.RECEIVER) RelayStage.WAITING_FOR_SOURCE else RelayStage.STOPPED,
                reason = reason,
            )
            promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    private fun startReceiverPlayback(mac: String) {
        if (mutableState.value.role != RelayRole.RECEIVER || audioPlayback != null || !playbackStarting.compareAndSet(false, true)) return
        val generation = playbackGeneration.incrementAndGet()
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // AirPodsService.localMac is the phone's Bluetooth identity, not the AirPods output.
        // Route verification must use the connected AirPods address saved as macAddress.
        val expectedAirPodsAddress = ServiceManager.getService()?.macAddress
            ?.takeIf(String::isNotBlank)
            ?: getSharedPreferences("settings", MODE_PRIVATE).getString("mac_address", null)
                ?.takeIf(String::isNotBlank)
        Log.i(TAG, "AirPods route identity available=${expectedAirPodsAddress != null}")
        val playback = RelayAudioPlayback(
            context = this,
            expectedAirPodsAddress = expectedAirPodsAddress,
            tuning = activeTuning,
            onFirstAudioFrame = {
                scope.launch {
                    if (generation != playbackGeneration.get() || audioPlayback?.hasAudibleAudio != true || mutableState.value.interruption != RelayInterruption.NONE) return@launch
                    mutableState.value = mutableState.value.copy(stage = RelayStage.STREAMING, audioActive = true)
                    sendControl(mac, RelaySessionControl.command(RelaySessionControl.AUDIO_STARTED))
                }
            },
            onBuffering = { buffering ->
                scope.launch {
                    if (generation != playbackGeneration.get() || audioPlayback?.isSuspended != false || mutableState.value.interruption != RelayInterruption.NONE) return@launch
                    if (!buffering && audioPlayback?.hasAudibleAudio != true) return@launch
                    mutableState.value = mutableState.value.copy(stage = if (buffering) RelayStage.BUFFERING else RelayStage.STREAMING)
                    sendControl(mac, RelaySessionControl.buffering(buffering))
                }
            },
            onInterruption = { interruption, unavailable -> scope.launch {
                if (generation != playbackGeneration.get()) return@launch
                updateInterruption(mac, interruption, unavailable)
                sendControl(mac, RelaySessionControl.interruption(interruption, unavailable))
            } },
            onOutputBuffer = { buffer -> scope.launch {
                if (generation == playbackGeneration.get()) mutableState.value = mutableState.value.copy(relayOutputBuffer = buffer)
            } },
            sourceClockOffsetNanos = { sourceClockOffsetNanos },
            onDelayMeasurement = { estimate, jitter, queue, arrivalAge ->
                scope.launch {
                    if (generation != playbackGeneration.get() || mutableState.value.interruption != RelayInterruption.NONE) return@launch
                    mutableState.value = mutableState.value.copy(
                        relayDelayEstimateMillis = estimate,
                        relayJitterTargetMillis = jitter,
                        relayAudioTrackQueueMillis = queue,
                        relayCaptureToReceiverMillis = arrivalAge,
                    )
                }
            },
            onFailure = { reason -> scope.launch {
                if (generation == playbackGeneration.get()) fail(mac, reason, notifyPeer = true)
            } },
        )
        // Stop must also be able to close playback while route setup is in flight.
        audioPlayback = playback
        scope.launch(Dispatchers.IO) {
            try {
                playback.start()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playbackGeneration.get() || audioPlayback !== playback) {
                        playback.close()
                        return@withContext
                    }
                    mutableState.value = mutableState.value.copy(stage = if (mutableState.value.interruption == RelayInterruption.NONE) RelayStage.WAITING_FOR_AUDIO else RelayStage.SUSPENDED, reason = null)
                    sendControl(mac, RelaySessionControl.command(RelaySessionControl.RECEIVER_READY))
                    forwardHeartRate(mac)
                    startClockSync(mac)
                }
            } catch (error: Exception) {
                playback.close()
                withContext(Dispatchers.Main.immediate) {
                    if (generation != playbackGeneration.get()) return@withContext
                    promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    sendControl(
                        mac,
                        RelaySessionControl.reason(
                            RelaySessionControl.RECEIVER_UNAVAILABLE,
                            error.message ?: "Bluetooth audio is unavailable",
                        ),
                    )
                    fail(mac, error.message ?: "Bluetooth audio is unavailable", notifyPeer = false)
                }
            } finally {
                playbackStarting.set(false)
            }
        }
    }

    private fun sendControl(mac: String, command: ByteArray): Boolean =
        runCatching { transports[mac]?.sendControl(command) == true }
            .onFailure { Log.w(TAG, "Relay control send failed", it) }.getOrDefault(false)

    private fun receiveTransportControl(mac: String, packet: ByteArray) {
        // Only the current authenticated transport participant may alter a session.
        if (!mutableState.value.peerMac.equals(mac, true) || transports[mac] == null) return
        if (stopTimeout != null && packet.firstOrNull() !in setOf(RelaySessionControl.STOP, RelaySessionControl.STOP_ACK, RelaySessionControl.SUSPENSION_EXPIRED)) return
        if (packet.isEmpty() || packet.size > MAX_CONTROL_PACKET_BYTES) {
            fail(mac, "Invalid relay control packet", notifyPeer = false)
            return
        }
        when (packet[0]) {
            RelaySessionControl.PLAYBACK_INTERRUPTION -> {
                if (mutableState.value.role != RelayRole.SOURCE) return
                val (interruption, unavailable) = RelaySessionControl.decodeInterruption(packet)
                updateInterruption(mac, interruption, unavailable)
            }
            RelaySessionControl.RESUME_REQUEST -> {
                if (packet.size == 1 && mutableState.value.role == RelayRole.RECEIVER) resumeRelay()
            }
            RelaySessionControl.SUSPENSION_EXPIRED -> {
                if (packet.size == 1 && mutableState.value.role == RelayRole.SOURCE) stopRemoteRelay(mac, getString(R.string.audio_relay_suspension_expired))
            }
            RelaySessionControl.CLOCK_SYNC_REQUEST -> {
                if (mutableState.value.role != RelayRole.SOURCE || packet.size != 17) return
                val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
                input.get()
                val id = input.long
                val receiverSend = input.long
                val sourceReceive = android.os.SystemClock.elapsedRealtimeNanos()
                val sourceSend = android.os.SystemClock.elapsedRealtimeNanos()
                sendControl(mac, RelaySessionControl.clockSyncResponse(id, receiverSend, sourceReceive, sourceSend))
            }
            RelaySessionControl.CLOCK_SYNC_RESPONSE -> {
                if (mutableState.value.role != RelayRole.RECEIVER || packet.size != 33) return
                val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
                input.get()
                val id = input.long
                val localSend = input.long
                val sourceReceive = input.long
                val sourceSend = input.long
                if (pendingClockSync.remove(id) != localSend) return
                val localReceive = android.os.SystemClock.elapsedRealtimeNanos()
                val sample = RelayClockSyncSample.calculate(localSend, sourceReceive, sourceSend, localReceive) ?: return
                val previousBest = bestClockSyncRttNanos
                if (sourceClockOffsetNanos == null || sample.roundTripNanos <= previousBest + CLOCK_SYNC_ACCEPTANCE_MARGIN_NANOS) {
                    val previous = sourceClockOffsetNanos
                    sourceClockOffsetNanos = if (previous == null) sample.remoteMinusLocalNanos else
                        ((previous.toDouble() * 0.8) + (sample.remoteMinusLocalNanos.toDouble() * 0.2)).toLong()
                    bestClockSyncRttNanos = minOf(previousBest, sample.roundTripNanos)
                    val uncertainty = ((sample.roundTripNanos / 2) / 1_000_000L).toInt()
                    mutableState.value = mutableState.value.copy(relayClockSyncUncertaintyMillis = uncertainty)
                    Log.i(TAG, "Relay clock sync offset=${sourceClockOffsetNanos}ns RTT=${sample.roundTripNanos / 1_000_000L}ms uncertainty~${uncertainty}ms")
                }
            }
            RelaySessionControl.RECEIVER_READY -> if (mutableState.value.role == RelayRole.SOURCE && audioCapture == null) {
                mutableState.value = mutableState.value.copy(
                    captureApprovalReady = true,
                    stage = if (mutableState.value.interruption == RelayInterruption.NONE) RelayStage.WAITING_FOR_AUDIO else RelayStage.SUSPENDED,
                    reason = null,
                )
            }
            RelaySessionControl.RECEIVER_UNAVAILABLE ->
                fail(mac, RelaySessionControl.decodeReason(packet), notifyPeer = false)
            RelaySessionControl.CAPTURE_STARTED -> if (mutableState.value.role == RelayRole.RECEIVER) {
                mutableState.value = mutableState.value.copy(stage = if (mutableState.value.interruption == RelayInterruption.NONE) RelayStage.WAITING_FOR_AUDIO else RelayStage.SUSPENDED, audioActive = false)
            }
            RelaySessionControl.SOURCE_AUDIO_ACTIVITY -> {
                if (packet.size != 2 || packet[1].toInt() !in 0..1) {
                    fail(mac, "Invalid source audio activity state", notifyPeer = false)
                    return
                }
                val active = packet[1].toInt() == 1
                mutableState.value = mutableState.value.copy(
                    audioActive = active,
                    stage = when {
                        mutableState.value.interruption != RelayInterruption.NONE -> RelayStage.SUSPENDED
                        !active -> RelayStage.WAITING_FOR_AUDIO
                        mutableState.value.role == RelayRole.SOURCE -> RelayStage.STREAMING
                        else -> mutableState.value.stage
                    },
                )
            }
            RelaySessionControl.STOP -> stopRemoteRelay(mac)
            RelaySessionControl.STOP_ACK -> {
                stopLocalAudio()
                closeTransport(mac)
                mutableState.value = RelayUiState(peerMac = mac, role = ownershipRole(mac), stage = RelayStage.STOPPED, reason = terminalStopReason)
                promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
            RelaySessionControl.AUDIO_STARTED -> if (mutableState.value.interruption == RelayInterruption.NONE)
                mutableState.value = mutableState.value.copy(stage = RelayStage.STREAMING, audioActive = true)
            RelaySessionControl.PLAYBACK_BUFFERING -> {
                if (packet.size != 2 || packet[1].toInt() !in 0..1) {
                    fail(mac, "Invalid playback health state", notifyPeer = false)
                    return
                }
                if (mutableState.value.interruption == RelayInterruption.NONE) mutableState.value = mutableState.value.copy(stage = if (packet[1].toInt() == 1) RelayStage.BUFFERING else RelayStage.STREAMING)
            }
            RelaySessionControl.HEART_RATE_SAMPLE -> {
                if (packet.size != 2 || (packet[1].toInt() and 0xff) == 0) {
                    fail(mac, "Invalid heart-rate sample", notifyPeer = false)
                    return
                }
                mutableState.value = mutableState.value.copy(heartRateBpm = packet[1].toInt() and 0xff, heartRateUpdatedAtMillis = System.currentTimeMillis())
            }
            RelaySessionControl.HEART_RATE_UNAVAILABLE ->
                mutableState.value = mutableState.value.copy(heartRateBpm = null, heartRateUpdatedAtMillis = null)
            RelaySessionControl.HEART_RATE_SHARING -> {
                if (packet.size != 4 || packet.drop(1).any { it.toInt() !in 0..1 }) {
                    fail(mac, "Invalid heart-rate sharing status", notifyPeer = false)
                    return
                }
                mutableState.value = mutableState.value.copy(
                    heartRateSharingActive = packet[1].toInt() == 1,
                    heartRateSharingBluetooth = packet[2].toInt() == 1,
                    heartRateSharingTickr = packet[3].toInt() == 1,
                )
            }
            else -> fail(mac, "Unknown relay control command", notifyPeer = false)
        }
    }

    private fun updateInterruption(mac: String, interruption: RelayInterruption, unavailable: Boolean) {
        val current = mutableState.value
        val suspended = interruption != RelayInterruption.NONE
        val expires = if (suspended) current.suspensionExpiresAtMillis ?: (System.currentTimeMillis() + SUSPENSION_TIMEOUT_MS) else null
        mutableState.value = current.copy(
            stage = if (suspended) RelayStage.SUSPENDED else RelayStage.WAITING_FOR_AUDIO,
            interruption = interruption,
            resumeUnavailable = unavailable,
            suspensionExpiresAtMillis = expires,
            audioActive = false,
            relayDelayEstimateMillis = null,
            relayAudioTrackQueueMillis = null,
            relayCaptureToReceiverMillis = null,
            relayEncodeMillis = null,
            relaySendCallMillis = null,
            reason = null,
        )
        Log.i(TAG, "Relay interruption=$interruption resumeUnavailable=$unavailable")
        if (!suspended) {
            suspensionTimeout?.cancel()
            suspensionTimeout = null
        } else if (current.role == RelayRole.RECEIVER && suspensionTimeout == null) {
            suspensionTimeout = scope.launch {
                delay(SUSPENSION_TIMEOUT_MS)
                if (mutableState.value.peerMac == mac && mutableState.value.stage == RelayStage.SUSPENDED) {
                    stopRelayWithReason(getString(R.string.audio_relay_suspension_expired))
                }
            }
        }
    }

    private fun onMedia(mac: String, packet: ByteArray) {
        if (mutableState.value.role != RelayRole.RECEIVER || !mutableState.value.peerMac.equals(mac, true)) return
        val count = receivedAudioPackets.incrementAndGet()
        if (count == 1L || count % AUDIO_PACKET_DIAGNOSTIC_INTERVAL == 0L) {
            Log.i(TAG, "Receiver media packets=$count bytes=${packet.size} playbackReady=${audioPlayback != null}")
        }
        audioPlayback?.offer(packet)
    }

    private fun startClockSync(mac: String) {
        clockSyncJob?.cancel()
        pendingClockSync.clear()
        sourceClockOffsetNanos = null
        bestClockSyncRttNanos = Long.MAX_VALUE
        mutableState.value = mutableState.value.copy(
            relayDelayEstimateMillis = null,
            relayClockSyncUncertaintyMillis = null,
            relayJitterTargetMillis = null,
            relayAudioTrackQueueMillis = null,
            relayCaptureToReceiverMillis = null,
            relayEncodeMillis = null,
            relaySendCallMillis = null,
        )
        clockSyncJob = scope.launch {
            while (mutableState.value.role == RelayRole.RECEIVER && mutableState.value.peerMac == mac && audioPlayback != null) {
                val id = clockSyncIds.incrementAndGet()
                val sentAt = android.os.SystemClock.elapsedRealtimeNanos()
                pendingClockSync[id] = sentAt
                if (!sendControl(mac, RelaySessionControl.clockSyncRequest(id, sentAt))) pendingClockSync.remove(id)
                delay(CLOCK_SYNC_PERIOD_MS)
                val cutoff = android.os.SystemClock.elapsedRealtimeNanos() - CLOCK_SYNC_EXPIRY_NANOS
                pendingClockSync.entries.removeIf { it.value < cutoff }
            }
        }
    }

    private fun sendNetworkSignal(mac: String, signal: WebRtcRelayTransport.Signal) {
        check(controller?.sendAuthenticated(mac, RelaySignalCodec.encode(signal)) == true) { "Authenticated network setup unavailable" }
    }

    private fun armNetworkSetupTimeout(mac: String) {
        networkSetupTimeout?.cancel()
        networkSetupTimeout = scope.launch {
            delay(if (mutableState.value.role == RelayRole.SOURCE) SOURCE_SETUP_TIMEOUT_MS else 10_000L)
            fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = false)
        }
    }

    private fun receiveNetworkSignal(mac: String, signal: WebRtcRelayTransport.Signal) {
        when (signal) {
            WebRtcRelayTransport.Signal.RevokeRelay -> {
                connections.remove(mac, DeviceConnectionType.WIFI_RELAY, propagate = false)
                if (pendingSwitch?.mac.equals(mac, true)) declinePendingSwitch()
                if (mutableState.value.peerMac.equals(mac, true)) stopRelay()
                controller?.sendAuthenticated(mac, RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelayAck))
            }
            WebRtcRelayTransport.Signal.RevokeRelayAck -> connections.acknowledgeRevocation(mac)
            is WebRtcRelayTransport.Signal.RequestPath -> {
                check(isPeerTrusted(mac))
                check(ownershipRole(mac) == RelayRole.RECEIVER)
                if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("audio_relay_allow_${mac.uppercase()}", true)) {
                    sendNetworkSignal(mac, WebRtcRelayTransport.Signal.PathUnavailable)
                    return
                }
                val current = mutableState.value
                val activeStream = current.role == RelayRole.RECEIVER && current.stage in
                    setOf(RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
                val reserving = current.role == RelayRole.RECEIVER && current.stage == RelayStage.CONNECTING
                when (RelayReceiverAdmission.decide(
                    requester = mac,
                    activeSender = current.peerMac,
                    active = activeStream,
                    reservedSender = current.peerMac.takeIf { reserving },
                    pendingSender = pendingSwitch?.mac,
                )) {
                    RelayAdmissionDecision.DUPLICATE_REQUEST -> {
                        if (pendingSwitch?.mac.equals(mac, true)) {
                            mutableState.value = current.copy(pendingRequestExpiresAtMillis = System.currentTimeMillis() + SWITCH_REQUEST_TIMEOUT_MS)
                        }
                        return
                    }
                    RelayAdmissionDecision.BUSY -> {
                        sendNetworkSignal(mac, WebRtcRelayTransport.Signal.ReceiverBusy)
                        return
                    }
                    RelayAdmissionDecision.REQUEST_SWITCH -> {
                        pendingSwitch = PendingSwitch(mac, signal)
                        pendingSwitchTimeout?.cancel()
                        pendingSwitchTimeout = scope.launch {
                            delay(SWITCH_REQUEST_TIMEOUT_MS)
                            if (pendingSwitch?.mac == mac) declinePendingSwitch()
                        }
                        mutableState.value = current.copy(
                            pendingSenderMac = mac,
                            pendingRequestExpiresAtMillis = System.currentTimeMillis() + SWITCH_REQUEST_TIMEOUT_MS,
                        )
                        sendNetworkSignal(mac, WebRtcRelayTransport.Signal.SwitchPending)
                        notifySessionChanged()
                        return
                    }
                    RelayAdmissionDecision.RESERVE_RECEIVER -> Unit
                }
                mutableState.value = current.copy(peerMac = mac, role = RelayRole.RECEIVER, stage = RelayStage.CONNECTING,
                    networkMode = signal.mode, reason = null,
                    reservationExpiresAtMillis = System.currentTimeMillis() + RESERVATION_TIMEOUT_MS)
                networkPolicy = signal.mode
                activeTuning = signal.tuning
                Log.i(TAG, "Relay audio profile negotiated from sender: $activeTuning")
                triedHotspotFallback = false
                acceptPathRequest(mac, signal)
            }
            is WebRtcRelayTransport.Signal.OfferPath -> {
                if (mutableState.value.peerMac != mac) return
                check(sourceSessionRequested && ownershipRole(mac) == RelayRole.SOURCE && networkNegotiating && selectedNetworkPath == null)
                activeTuning = signal.tuning
                val path = RelayNetworkPath.sender(this, signal.address, signal.prefix)
                selectedNetworkPath = path
                startTransport(mac, path, signal.address, signal.tuning)
                sendNetworkSignal(mac, WebRtcRelayTransport.Signal.ConfirmPath(path.address))
                // Audio direction, not Bluetooth connection election, selects the offerer.
                transports[mac]?.createOffer()
            }
            is WebRtcRelayTransport.Signal.ConfirmPath -> {
                if (mutableState.value.peerMac != mac) return
                check(ownershipRole(mac) == RelayRole.RECEIVER && networkNegotiating && !transports.containsKey(mac))
                val path = checkNotNull(selectedNetworkPath)
                check(path.address != signal.address && path.contains(signal.address) && path.available(this))
                startTransport(mac, path, signal.address, activeTuning)
            }
            WebRtcRelayTransport.Signal.SwitchPending -> if (mutableState.value.role == RelayRole.SOURCE && mutableState.value.peerMac == mac) {
                mutableState.value = mutableState.value.copy(waitingForReceiverApproval = true)
            }
            WebRtcRelayTransport.Signal.ReceiverBusy -> if (mutableState.value.role == RelayRole.SOURCE && mutableState.value.peerMac == mac) {
                sourceSessionRequested = false
                networkSetupTimeout?.cancel()
                reservationTimeout?.cancel()
                reservationTimeout = null
                mutableState.value = mutableState.value.copy(stage = RelayStage.FAILED, receiverBusy = true, reason = getString(R.string.audio_relay_receiver_busy))
            }
            WebRtcRelayTransport.Signal.CancelRequest -> {
                if (pendingSwitch?.mac == mac) {
                    pendingSwitch = null
                    pendingSwitchTimeout?.cancel()
                    pendingSwitchTimeout = null
                    mutableState.value = mutableState.value.copy(pendingSenderMac = null, pendingRequestExpiresAtMillis = null)
                } else if (mutableState.value.role == RelayRole.RECEIVER && mutableState.value.peerMac == mac &&
                    mutableState.value.stage == RelayStage.CONNECTING && transports[mac] == null) {
                    stopLocalAudio()
                    mutableState.value = RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.WAITING_FOR_SOURCE)
                }
            }
            is WebRtcRelayTransport.Signal.PeerSessionStatus -> {
                if (!isPeerTrusted(mac)) return
                val current = mutableState.value
                if (current.role == RelayRole.RECEIVER ||
                    (current.peerMac != mac && current.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED))) return
                mutableState.value = mutableState.value.copy(
                    peerMac = mac,
                    remoteReceiverActive = signal.active,
                    remoteActiveSenderName = signal.senderName.takeIf { signal.active && it.isNotBlank() },
                )
            }
            WebRtcRelayTransport.Signal.PathUnavailable -> if (mutableState.value.peerMac == mac) {
                val oldPath = selectedNetworkPath
                val fallback = if (mutableState.value.role == RelayRole.RECEIVER && networkPolicy == "auto" && !triedHotspotFallback && oldPath != null) {
                    runCatching { RelayNetworkPath.receiver(this, "hotspot") }.getOrNull()
                } else null
                if (fallback != null && fallback != oldPath) {
                    triedHotspotFallback = true
                    selectedNetworkPath = fallback
                    mutableState.value = mutableState.value.copy(networkMode = "hotspot")
                    Log.i(TAG, "Automatic route retry using available device hotspot interface ${fallback.interfaceName}")
                    sendNetworkSignal(mac, WebRtcRelayTransport.Signal.OfferPath(fallback.address, fallback.prefix, activeTuning))
                } else fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = false)
            }
            else -> if (mutableState.value.peerMac == mac) transports[mac]?.accept(signal)
        }
    }

    private fun acceptPathRequest(mac: String, signal: WebRtcRelayTransport.Signal.RequestPath) {
        // A fresh authenticated Start reserves the receiver before route negotiation.
        transports.keys.toList().forEach(::closeTransport)
        val path = RelayNetworkPath.receiver(this, signal.mode)
        selectedNetworkPath = path
        networkNegotiating = true
        mutableState.value = mutableState.value.copy(peerMac = mac, role = RelayRole.RECEIVER,
            stage = RelayStage.CONNECTING, networkMode = signal.mode, reason = null,
            reservationExpiresAtMillis = System.currentTimeMillis() + RESERVATION_TIMEOUT_MS)
        reservationTimeout?.cancel()
        reservationTimeout = scope.launch {
            delay(RESERVATION_TIMEOUT_MS)
            if (mutableState.value.peerMac == mac && mutableState.value.stage !in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)) {
                fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = true)
            }
        }
        armNetworkSetupTimeout(mac)
        Log.i(TAG, "Network requested: mode=${signal.mode} interface=${path.interfaceName} address=${path.address}/${path.prefix}")
        sendNetworkSignal(mac, WebRtcRelayTransport.Signal.OfferPath(path.address, path.prefix, activeTuning))
    }

    @SuppressLint("MissingPermission")
    private fun ownershipRoleForLocal(): RelayRole = if (
        ServiceManager.getService()?.isConnected() == true || ServiceManager.getService()?.isA2dpConnected() == true
    ) RelayRole.RECEIVER else RelayRole.NONE

    private fun startTransport(mac: String, path: RelayNetworkPath, peerAddress: String, tuning: RelayAudioTuning = activeTuning) {
        Log.i(TAG, "Starting pinned WebRTC transport: ${path.interfaceName} ${path.address} -> $peerAddress")
        transports.keys.toList().forEach(::closeTransport)
        lateinit var transport: WebRtcRelayTransport
        transport = WebRtcRelayTransport(
            context = this,
            networkPath = path,
            peerAddress = peerAddress,
            tuning = tuning,
            onSignal = { signal ->
                Log.d(TAG, "Sending authenticated WebRTC signal: ${signal.javaClass.simpleName}")
                val payload = runCatching { RelaySignalCodec.encode(signal) }.getOrNull()
                if (payload == null || controller?.sendAuthenticated(mac, payload) != true) {
                    fail(mac, "Unable to send authenticated connection setup")
                }
            },
            onMediaPacket = { packet -> if (transports[mac] === transport) onMedia(mac, packet) },
            onControlPacket = { packet -> scope.launch {
                if (transports[mac] !== transport) return@launch
                runCatching { receiveTransportControl(mac, packet) }
                    .onFailure { fail(mac, "Invalid relay control packet", notifyPeer = true) }
            } },
        )
        transports[mac] = transport
        transportJobs[mac] = scope.launch {
            transport.state.collect { connectionState ->
                if (mutableState.value.peerMac != mac || transports[mac] !== transport || stopTimeout != null) return@collect
                Log.i(TAG, "Local WebRTC transport state: $connectionState")
                when (connectionState) {
                    WebRtcRelayTransport.State.CONNECTED -> {
                        networkSetupTimeout?.cancel()
                        networkSetupTimeout = null
                        networkNegotiating = false
                        reservationTimeout?.cancel()
                        reservationTimeout = null
                        mutableState.value = mutableState.value.copy(stage = RelayStage.WAITING_FOR_AUDIO, reason = null)
                        if (mutableState.value.role == RelayRole.RECEIVER) startReceiverPlayback(mac)
                    }
                    WebRtcRelayTransport.State.FAILED -> fail(mac, getString(R.string.audio_relay_network_unavailable), notifyPeer = true)
                    WebRtcRelayTransport.State.DISCONNECTED,
                    WebRtcRelayTransport.State.CLOSED -> if (mutableState.value.stage in setOf(
                            RelayStage.CONNECTING,
                            RelayStage.WAITING_FOR_AUDIO,
                            RelayStage.STREAMING,
                            RelayStage.BUFFERING,
                            RelayStage.SUSPENDED,
                        )) {
                        // A closed transport must release its receiver reservation even
                        // before the first audio packet. Otherwise the receiver stays
                        // marked active and silently rejects the sender's retry as a
                        // duplicate, so Auto network selection never runs again.
                        fail(mac, "Wi-Fi connection lost", notifyPeer = false)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun fail(mac: String, reason: String, notifyPeer: Boolean = false) {
        Log.e(TAG, "Relay failed: $reason")
        if (notifyPeer) sendControl(mac, RelaySessionControl.reason(RelaySessionControl.RECEIVER_UNAVAILABLE, reason))
        stopLocalAudio()
        mutableState.value = mutableState.value.copy(peerMac = mac, stage = RelayStage.FAILED, reason = reason)
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun promote(type: Int) {
        foregroundStarted = true
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or type)
    }

    private fun buildNotification(): Notification {
        val state = mutableState.value
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.audio_relay_playback), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent().setClassName(packageName, "io.automated.ventures.everypods.MainActivity")
                .putExtra(EXTRA_OPEN_PEER_MAC, state.peerMac)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RelayPreviewService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val statusText = when {
            state.stage == RelayStage.SUSPENDED -> getString(when {
                state.resumeUnavailable -> R.string.audio_relay_resume_unavailable
                state.interruption == RelayInterruption.TEMPORARY -> R.string.audio_relay_suspended_temporary
                else -> R.string.audio_relay_suspended_local
            }, if (state.role == RelayRole.SOURCE) deviceName(state.peerMac) else getString(R.string.audio_relay_route_this_device))
            state.reason != null && state.stage in setOf(RelayStage.STOPPED, RelayStage.WAITING_FOR_SOURCE) -> state.reason
            state.pendingSenderMac != null -> getString(R.string.audio_relay_switch_request, deviceName(state.pendingSenderMac), deviceName(state.peerMac))
            state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED) && state.role == RelayRole.SOURCE -> getString(R.string.audio_relay_sending_to_peer, deviceName(state.peerMac))
            state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED) && state.role == RelayRole.RECEIVER -> getString(R.string.audio_relay_receiving_from_peer, deviceName(state.peerMac))
            else -> when (state.stage) {
            RelayStage.STREAMING -> getString(R.string.audio_relay_streaming)
            RelayStage.BUFFERING -> getString(R.string.audio_relay_buffering)
            RelayStage.WAITING_FOR_AUDIO -> getString(R.string.audio_relay_waiting_audio)
            RelayStage.CONNECTING -> getString(R.string.audio_relay_connecting)
            RelayStage.VERIFYING -> getString(R.string.audio_relay_verify_heading)
            RelayStage.FAILED -> getString(R.string.audio_relay_action_needed)
            else -> getString(R.string.audio_relay_ready_receiver)
            }
        }
        val active = state.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.audio_relay_playback))
            .setContentText(statusText)
            .setContentIntent(open)
            .setOngoing(active)
        if (state.stage == RelayStage.SUSPENDED && state.pendingSenderMac == null) {
            val resume = PendingIntent.getService(this, 5,
                Intent(this, RelayPreviewService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, getString(R.string.audio_relay_resume), resume)
        }
        if (active) builder.addAction(0, getString(R.string.audio_relay_stop), stop)
        if (state.pendingSenderMac != null) {
            val approve = PendingIntent.getService(this, 3,
                Intent(this, RelayPreviewService::class.java).setAction(ACTION_APPROVE_SWITCH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val decline = PendingIntent.getService(this, 4,
                Intent(this, RelayPreviewService::class.java).setAction(ACTION_DECLINE_SWITCH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, getString(R.string.audio_relay_switch_to_peer, deviceName(state.pendingSenderMac)), approve)
            builder.addAction(0, getString(R.string.audio_relay_keep_current), decline)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(mac: String?): String {
        if (mac.isNullOrBlank()) return getString(R.string.audio_relay_device_fallback)
        return runCatching {
            getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac)?.name
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: getString(R.string.audio_relay_device_fallback)
    }

    private fun notifySessionChanged() {
        if (foregroundStarted) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        stopRelay()
        controller?.close()
        controller = null
        scope.cancel()
        getSharedPreferences("settings", MODE_PRIVATE).edit {
            putBoolean("audio_relay_local_active", false)
            putBoolean("audio_relay_local_suspended", false)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RelayPreviewService"
        private const val CHANNEL_ID = "everypods_audio_relay"
        private const val NOTIFICATION_ID = 74102
        private const val MAX_CONTROL_PACKET_BYTES = 4_096
        private const val STOP_ACK_TIMEOUT_MS = 1_000L
        private const val SWITCH_REQUEST_TIMEOUT_MS = RelayReceiverAdmission.SWITCH_REQUEST_TIMEOUT_MS
        private const val RESERVATION_TIMEOUT_MS = RelayReceiverAdmission.RESERVATION_TIMEOUT_MS
        private const val SUSPENSION_TIMEOUT_MS = 120_000L
        private const val SOURCE_SETUP_TIMEOUT_MS = 65_000L
        private const val AUDIO_PACKET_DIAGNOSTIC_INTERVAL = 50L
        private const val CLOCK_SYNC_PERIOD_MS = 1_000L
        private const val CLOCK_SYNC_EXPIRY_NANOS = 5_000_000_000L
        private const val CLOCK_SYNC_ACCEPTANCE_MARGIN_NANOS = 10_000_000L
        const val ACTION_START = "io.automated.ventures.everypods.relay.START"
        const val ACTION_RESUME = "io.automated.ventures.everypods.relay.RESUME"
        const val ACTION_STOP = "io.automated.ventures.everypods.relay.STOP"
        const val ACTION_FORGET_PEER = "io.automated.ventures.everypods.relay.FORGET_PEER"
        const val ACTION_APPROVE_SWITCH = "io.automated.ventures.everypods.relay.APPROVE_SWITCH"
        const val ACTION_DECLINE_SWITCH = "io.automated.ventures.everypods.relay.DECLINE_SWITCH"
        const val EXTRA_PEER_MAC = "peer_mac"
        const val EXTRA_OPEN_PEER_MAC = "audio_relay_open_peer_mac"
        const val EXTRA_OPEN_START = "audio_relay_open_start"

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RelayPreviewService::class.java).setAction(ACTION_START),
            )
        }
    }
}
