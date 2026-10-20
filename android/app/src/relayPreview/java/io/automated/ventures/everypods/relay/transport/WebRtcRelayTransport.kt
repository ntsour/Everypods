/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import io.automated.ventures.everypods.relay.RelayAudioTuning

/**
 * WebRTC's local-network transport for relay media and control.
 *
 * The caller must establish peer identity and authenticate signaling before creating this
 * transport. ICE is configured without public STUN/TURN services, so candidate gathering is
 * limited to available local interfaces. WebRTC provides DTLS/SCTP over UDP encryption. Audio
 * packets use a separate unordered, deadline-limited channel; session commands use reliable,
 * ordered delivery.
 */
class WebRtcRelayTransport(
    context: Context,
    private val networkPath: RelayNetworkPath,
    private val peerAddress: String,
    private val tuning: RelayAudioTuning = RelayAudioTuning.DEFAULT,
    private val onSignal: (Signal) -> Unit,
    private val onMediaPacket: (ByteArray) -> Unit,
    private val onControlPacket: (ByteArray) -> Unit,
) : AutoCloseable {
    sealed interface Signal {
        data class RequestPath(val mode: String, val tuning: RelayAudioTuning = RelayAudioTuning.DEFAULT) : Signal {
            init { require(mode in setOf("auto", "wifi", "hotspot")) }
        }
        data object SwitchPending : Signal
        data object ReceiverBusy : Signal
        data object RevokeRelay : Signal
        data object RevokeRelayAck : Signal
        data object CancelRequest : Signal
        data class PeerSessionStatus(val active: Boolean, val senderName: String) : Signal
        data class OfferPath(val address: String, val prefix: Int, val tuning: RelayAudioTuning = RelayAudioTuning.DEFAULT) : Signal
        data class ConfirmPath(val address: String) : Signal
        data object PathUnavailable : Signal
        data class Description(val type: SessionDescription.Type, val sdp: String) : Signal
        data class Candidate(val mid: String?, val lineIndex: Int, val value: String) : Signal
    }

    enum class State { NEW, GATHERING, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

    private val closed = AtomicBoolean(false)
    @Volatile private var ownerThread: Thread? = null
    // Never dispose WebRTC from its signaling callbacks. All native calls and
    // callbacks use this owner so sends cannot race native resource teardown.
    private val owner = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EveryPodsRelayTransport").also { ownerThread = it }
    }
    private val mutableState = MutableStateFlow(State.NEW)
    val state: StateFlow<State> = mutableState

    private lateinit var factory: PeerConnectionFactory
    private lateinit var peer: PeerConnection
    private var media: DataChannel? = null
    private var control: DataChannel? = null
    private var pendingLocalDescription: SessionDescription? = null
    private var localDescriptionSent = false
    private val pendingLocalCandidates = ArrayDeque<IceCandidate>()
    private val pendingRemoteCandidates = ArrayDeque<IceCandidate>()
    private var remoteDescriptionSet = false

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = onOwner {
            Log.i(TAG, "ICE connection state: $state")
            mutableState.value = when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    if (channelsOpen()) State.CONNECTED else State.CONNECTING
                PeerConnection.IceConnectionState.DISCONNECTED -> State.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> State.FAILED
                PeerConnection.IceConnectionState.CLOSED -> State.CLOSED
                else -> State.CONNECTING
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = onOwner {
            Log.i(TAG, "ICE gathering state: $state")
            if (state == PeerConnection.IceGatheringState.GATHERING) mutableState.value = State.GATHERING
        }
        override fun onIceCandidate(candidate: IceCandidate) = onOwner {
            if (!allowedCandidate(candidate.sdp, networkPath.address)) return@onOwner
            Log.i(TAG, "Local ICE candidate gathered: ${candidateRoute(candidate)}")
            if (localDescriptionSent) emitCandidate(candidate) else pendingLocalCandidates.addLast(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = onOwner {
            when (channel.label()) {
                MEDIA_CHANNEL -> { media = channel; observe(channel, onMediaPacket) }
                CONTROL_CHANNEL -> { control = channel; observe(channel, onControlPacket) }
                else -> channel.close()
            }
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = onOwner {
            Log.i(TAG, "Peer connection state: $state")
            if (state == PeerConnection.PeerConnectionState.FAILED) mutableState.value = State.FAILED
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED) mutableState.value = State.DISCONNECTED
            if (state == PeerConnection.PeerConnectionState.CLOSED) mutableState.value = State.CLOSED
        }
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) = onOwner {
            Log.i(TAG, "Selected network route: local=${candidateRoute(event.local)} remote=${candidateRoute(event.remote)}")
            // WebRTC can redact a peer-reflexive address in this diagnostic callback.
            // Remote host candidates are filtered before ICE; all local sockets
            // are restricted to the negotiated adapter by the network monitor.
            if (!allowedCandidate(event.local.sdp, networkPath.address)) {
                fail("ICE selected a route outside the negotiated network")
            }
        }
        override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent) {
            Log.w(TAG, "ICE candidate error: ${event.errorCode} ${event.errorText}")
        }
    }

    init {
        callOnOwner {
            val appContext = context.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
            )
            // The custom monitor exposes exactly the negotiated adapter, including
            // downstream hotspot interfaces omitted by ConnectivityManager.
            RelayNetworkMonitor.install(networkPath)
            val options = PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
            }
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
            Log.i(TAG, "Pinned relay interface=${networkPath.interfaceName} address=${networkPath.address} handle=${networkPath.networkHandle} peer=$peerAddress")
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }
            peer = requireNotNull(factory.createPeerConnection(config, observer)) {
                "Could not create local WebRTC peer connection"
            }
        }
    }

    /** Create an offer and the two channels. Send resulting Signal values over authenticated control. */
    fun createOffer() = onOwner {
        createChannels()
        peer.createOffer(createObserver(::setLocalDescription), org.webrtc.MediaConstraints())
    }

    /** Apply an authenticated remote description. Offer recipients create the answer. */
    fun accept(signal: Signal) = onOwner {
        when (signal) {
            is Signal.Candidate -> {
                if (!allowedCandidate(signal.value, peerAddress)) return@onOwner
                val candidate = IceCandidate(signal.mid, signal.lineIndex, signal.value)
                if (!remoteDescriptionSet) {
                    pendingRemoteCandidates.addLast(candidate)
                } else if (!peer.addIceCandidate(candidate)) {
                    fail("Remote ICE candidate rejected")
                }
            }
            is Signal.Description -> {
                val remote = SessionDescription(signal.type, filterSdp(signal.sdp, peerAddress))
                peer.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() = onOwner remoteSet@{
                        remoteDescriptionSet = true
                        while (pendingRemoteCandidates.isNotEmpty()) {
                            val candidate = pendingRemoteCandidates.removeFirst()
                            if (!peer.addIceCandidate(candidate)) {
                                fail("Remote ICE candidate rejected")
                                return@remoteSet
                            }
                        }
                        if (signal.type == SessionDescription.Type.OFFER) {
                            peer.createAnswer(createObserver(::setLocalDescription), org.webrtc.MediaConstraints())
                        }
                    }
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = fail("Remote SDP callback error: $error")
                    override fun onSetFailure(error: String) = fail("Remote SDP set failed: $error")
                }, remote)
            }
            else -> fail("Network negotiation must complete before WebRTC signaling")
        }
    }

    fun sendMedia(packet: ByteArray): Boolean {
        if (closed.get()) return false
        return runCatching {
            callOnOwner {
                if (closed.get()) return@callOnOwner false
                val channel = media ?: return@callOnOwner false
                // Keep at most about 200 ms of music queued. SCTP cannot discard
                // a stale message that has not yet left the local send queue.
                if (channel.bufferedAmount() > MAX_BUFFERED_MEDIA_BYTES) return@callOnOwner false
                send(channel, packet)
            }
        }.getOrDefault(false)
    }

    fun sendControl(packet: ByteArray): Boolean {
        if (closed.get()) return false
        return runCatching { callOnOwner { send(control, packet) } }.getOrDefault(false)
    }

    private fun send(channel: DataChannel?, packet: ByteArray): Boolean {
        if (closed.get() || channel?.state() != DataChannel.State.OPEN) return false
        val copied = packet.copyOf()
        return runCatching { channel.send(DataChannel.Buffer(ByteBuffer.wrap(copied), true)) }.getOrDefault(false)
    }

    private fun createChannels() {
        if (media != null || control != null) return
        val mediaInit = DataChannel.Init().apply {
            ordered = false
            // SCTP drops stale media instead of building an ever-growing retransmit queue.
            maxRetransmitTimeMs = tuning.retransmissionMs
        }
        val controlInit = DataChannel.Init().apply { ordered = true }
        media = requireNotNull(peer.createDataChannel(MEDIA_CHANNEL, mediaInit))
        control = requireNotNull(peer.createDataChannel(CONTROL_CHANNEL, controlInit))
        observe(media!!, onMediaPacket)
        observe(control!!, onControlPacket)
    }

    private fun observe(channel: DataChannel, receive: (ByteArray) -> Unit) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() = onOwner {
                publishChannelState(channel)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed.get() || !buffer.binary) return
                val view = buffer.data.slice()
                val packet = ByteArray(view.remaining())
                view.get(packet)
                // Copy native callback memory before returning, then invoke app code
                // on our owner; STOP/STOP_ACK may close this very transport.
                onOwner {
                    runCatching { receive(packet) }
                        .onFailure { fail("Data channel message rejected: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        })
        // onDataChannel is dispatched to our owner asynchronously. A remote-created
        // channel may already be OPEN when we register: WebRTC does not replay
        // that transition, so readiness must also inspect its current state.
        publishChannelState(channel)
    }

    private fun publishChannelState(channel: DataChannel) {
        Log.i(TAG, "Data channel ${channel.label()} state: ${channel.state()}")
        if (channelsOpen()) mutableState.value = State.CONNECTED
    }

    private fun channelsOpen(): Boolean =
        media?.state() == DataChannel.State.OPEN && control?.state() == DataChannel.State.OPEN

    private fun createObserver(onSuccess: (SessionDescription) -> Unit): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onOwner { onSuccess(description) }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { fail("SDP create failed: $error") }
        override fun onSetFailure(error: String) { fail("SDP set failed: $error") }
    }

    private fun setLocalDescription(description: SessionDescription) {
        pendingLocalDescription = description
        peer.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() = onOwner {
                val local = pendingLocalDescription ?: return@onOwner
                pendingLocalDescription = null
                localDescriptionSent = true
                onSignal(Signal.Description(local.type, filterSdp(local.description, networkPath.address)))
                while (pendingLocalCandidates.isNotEmpty()) emitCandidate(pendingLocalCandidates.removeFirst())
            }
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = fail("Local SDP callback error: $error")
            override fun onSetFailure(error: String) = fail("Local SDP set failed: $error")
        }, description)
    }

    private fun emitCandidate(candidate: IceCandidate) {
        onSignal(Signal.Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
    }

    private fun allowedCandidate(value: String, address: String): Boolean {
        val fields = value.trim().split(Regex("\\s+"))
        return fields.getOrNull(2).equals("udp", ignoreCase = true) && fields.getOrNull(4) == address
    }

    private fun filterSdp(sdp: String, address: String): String = sdp.split("\r\n")
        .filter { !it.startsWith("a=candidate:") || allowedCandidate(it.removePrefix("a="), address) }
        .joinToString("\r\n")

    /** Log routing evidence without SDP credentials or candidate extensions. */
    private fun candidateRoute(candidate: IceCandidate): String {
        val fields = candidate.sdp.trim().split(Regex("\\s+"))
        val type = candidate.sdp.substringAfter(" typ ", "unknown").substringBefore(' ')
        return "type=$type protocol=${fields.getOrNull(2)} address=${fields.getOrNull(4)} port=${fields.getOrNull(5)}"
    }

    private fun fail(reason: String) {
        if (closed.get()) return
        Log.e(TAG, reason)
        mutableState.value = State.FAILED
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // A replacement factory must not reuse the old singleton network monitor.
        // Await native teardown before installing the next interface selection.
        callOnOwner {
            try {
                for (channel in listOfNotNull(media, control)) {
                    channel.unregisterObserver()
                    channel.close()
                    channel.dispose()
                }
                media = null
                control = null
                // close() alone leaves the native peer referencing factory-owned
                // threads. dispose() must release the peer before the factory.
                peer.dispose()
                factory.dispose()
                Log.i(TAG, "Transport native resources disposed")
            } finally {
                mutableState.value = State.CLOSED
                owner.shutdown()
            }
        }
    }

    private fun onOwner(block: () -> Unit) {
        if (closed.get()) return
        try {
            owner.execute { if (!closed.get()) block() }
        } catch (error: RejectedExecutionException) {
            // A callback can arrive between the initial closed check and shutdown.
            if (!closed.get()) throw error
        }
    }

    private fun <T> callOnOwner(block: () -> T): T =
        if (Thread.currentThread() === ownerThread) block()
        else owner.submit(Callable { block() }).get()

    companion object {
        private const val TAG = "WebRtcRelayTransport"
        private const val MEDIA_CHANNEL = "everypods-relay-media-v1"
        private const val CONTROL_CHANNEL = "everypods-relay-control-v1"
        private const val MAX_BUFFERED_MEDIA_BYTES = 6 * 1024L
    }

}
