/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program, if not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.utils.peer.PeerRetryPolicy
import io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics
import io.automated.ventures.everypods.utils.peer.PeerConnectionError
import io.automated.ventures.everypods.utils.peer.PeerConnectionState
import io.automated.ventures.everypods.utils.peer.PeerConnectionKey
import io.automated.ventures.everypods.utils.peer.PeerConnection
import io.automated.ventures.everypods.utils.peer.PeerConnectionSupervisor
import io.automated.ventures.everypods.utils.peer.PeerV2Frame
import io.automated.ventures.everypods.utils.peer.PeerV2Message
import io.automated.ventures.everypods.utils.peer.PeerHeartbeat
import io.automated.ventures.everypods.utils.peer.PeerHeartbeatAction
import io.automated.ventures.everypods.utils.peer.PeerConnectDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private typealias PeerLink = PeerConnection

// RFCOMM client used when this device acts as the client side of an Android↔Android
// pair (role election: the lower-name device clients to the higher-name peer). Each
// configured peer where we are the client gets its own [PeerLink] — independent
// socket, retry/backoff loop, and keep-alive — so one peer dropping (Doze, range,
// OEM kill) never tears down the links to the others. Exchanges the same 4-byte
// packet protocol used by the Windows tray client and the server side.
object CrossDeviceClient {
    private const val TAG = "CrossDeviceClient"
    private val RFCOMM_UUID = java.util.UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")
    private val RFCOMM_V2_UUID = java.util.UUID.fromString("97ce0b4d-8e2f-4a6d-b6cd-d69d362e75ac")

    // Interval for keep-alive pings while an RFCOMM channel is idle.
    // Sends REQUEST_CONNECTION_STATUS so Android's BT power-management doesn't
    // drop the ACL link between handover events.
    private const val KEEPALIVE_INTERVAL_MS = 20_000L

    // While the AirPods are connected here, defer ordinary retries to protect
    // A2DP+AACP. Retry periodically so a peer that returns to range can recover
    // without user action; ACL_CONNECTED can wake a single earlier attempt.
    // These probes still need physical validation for audio impact.
    private const val HEADSET_HELD_RECHECK_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    private const val V2_HEARTBEAT_MS = 10_000L
    private const val V2_RETRY_MS = 10 * 60 * 1_000L

    private val links = ConcurrentHashMap<String, PeerLink>()
    private val generationCounter = AtomicLong(0L)
    private val peerSupervisor = PeerConnectionSupervisor()
    private val mutableStates = MutableStateFlow<Map<String, PeerConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, PeerConnectionState>> = mutableStates.asStateFlow()
    private val mutableErrors = MutableStateFlow<Map<String, PeerConnectionError>>(emptyMap())
    val errors: StateFlow<Map<String, PeerConnectionError>> = mutableErrors.asStateFlow()

    /** True when at least one peer link is live. Kept as a no-arg property for the
     *  existing `isPeerConnected` / UI-poll call-sites. */
    val isConnected: Boolean get() = links.values.any { it.isConnected }

    fun isConnected(mac: String): Boolean = links[mac.uppercase()]?.isConnected == true

    val connectedPeers: Set<String>
        get() = links.values.filter { it.isConnected }.map { it.mac }.toSet()

    /**
     * Reconcile the set of live client links against [peers]: stop links to peers no
     * longer in the set, start links to newly added peers, and leave already-running
     * links untouched. Idempotent — safe to call on every [CrossDevice.init].
     */
    @SuppressLint("MissingPermission")
    fun start(adapter: BluetoothAdapter, peers: Set<String>) {
        val normalizedPeers = peers.mapTo(mutableSetOf()) { it.uppercase() }
        (links.keys - normalizedPeers).forEach { stop(it) }
        normalizedPeers.forEach { mac ->
            val existing = links[mac]
            if (existing == null) startLink(adapter, mac)
            else if (mutableStates.value[mac] == PeerConnectionState.WaitingForPermission) {
                // CrossDevice.start is called from app/service re-entry after Android
                // permissions may have changed. Wake exactly this owned loop to
                // re-check; a still-denied permission returns to the wait state.
                existing.bluetoothWake.trySend(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLink(
        adapter: BluetoothAdapter,
        peerMac: String,
        manualHeldHeadsetAttemptRequested: Boolean = false,
    ) {
        val link = PeerLink(peerMac.uppercase(), generationCounter.incrementAndGet())
        link.manualHeldHeadsetAttemptRequested = manualHeldHeadsetAttemptRequested
        links[link.mac] = link
        publishState(link, if (adapter.isEnabled) PeerConnectionState.Connecting(link.generation)
            else PeerConnectionState.WaitingForBluetooth)
        link.job = peerSupervisor.launch(link) {
            var retryAttempt = 0
            var lastError: PeerConnectionError? = null
            while (isActive) {
                if (links[link.mac] !== link) break
                if (!adapter.isEnabled) {
                    publishState(link, PeerConnectionState.WaitingForBluetooth)
                    link.bluetoothWake.receive()
                    continue
                }
                if (CrossDevice.isConnectedTo(link.mac)) {
                    Log.d(TAG, "[${link.mac}] already reachable via inbound socket — deferring outbound client")
                    val inboundDelay = launch { delay(link.inboundRecheckMs) }
                    link.backoffJob = inboundDelay
                    inboundDelay.join()
                    link.backoffJob = null
                    continue
                }
                // Don't page the peer while we hold the AirPods — the failed
                // RFCOMM connect attempts jam the radio and drop the headset.
                // A deliberate user retry can bypass this guard once.
                val localAirPodsConnected = ServiceManager.getService()?.isConnected() == true
                if (!CrossDeviceReconnectPolicy.mayAttempt(
                        localAirPodsConnected = localAirPodsConnected,
                        manualAttemptRequested = link.manualHeldHeadsetAttemptRequested,
                        reachabilityProbeRequested = link.reachabilityProbeRequested,
                    )
                ) {
                    Log.d(TAG, "[${link.mac}] AirPods connected here — deferring connect to protect the headset link")
                    val heldHeadsetDelay = launch { delay(HEADSET_HELD_RECHECK_MS) }
                    link.heldHeadsetDelayJob = heldHeadsetDelay
                    heldHeadsetDelay.join()
                    link.heldHeadsetDelayJob = null
                    if (!isActive || links[link.mac] !== link) break
                    link.reachabilityProbeRequested = true
                    continue
                }
                link.reachabilityProbeRequested = false
                if (link.manualHeldHeadsetAttemptRequested) {
                    link.manualHeldHeadsetAttemptRequested = false
                    Log.d(TAG, "[$peerMac] attempting user-requested RFCOMM reconnect")
                }
                try {
                    publishState(link, PeerConnectionState.Connecting(link.generation))
                    val device = adapter.getRemoteDevice(link.mac)
                    // W5 instrumentation: resolve the peer's display name so logs make
                    // MAC↔device identity unambiguous (which MAC is the PC vs each phone).
                    val peerName = try { device.name } catch (_: Exception) { null } ?: "?"
                    Log.d(TAG, "[${link.mac}] CLIENT connect attempt (name='$peerName', generation=${link.generation})")
                    // Insecure RFCOMM: same rationale as the server side. Avoids
                    // a per-connect auth step that races with other BT activity
                    // on the same ACL and intermittently fails with "read ret: -1".
                    val s = peerSupervisor.createSocket(adapter, link.mac, RFCOMM_UUID)
                    link.socket = s // Publish before connect so Stop can close a pending socket.
                    val connectDeadline = PeerConnectDeadline(
                        scope = this,
                        timeoutMs = CONNECT_TIMEOUT_MS,
                        isCurrent = { links[link.mac] === link && link.socket === s },
                        isConnected = { s.isConnected },
                        closeSocket = {
                            Log.w(TAG, "[$peerMac] connect timed out; closing pending RFCOMM socket")
                            s.close()
                        },
                    )
                    try {
                        try {
                            peerSupervisor.connectSerialized {
                                if (link.socket !== s || connectDeadline.hasTimedOut()) throw java.io.IOException("connect_cancelled")
                                s.connect()
                                if (connectDeadline.hasTimedOut()) throw java.net.SocketTimeoutException("connect timed out")
                                if (link.socket !== s || !s.isConnected) throw java.io.IOException("connect_closed")
                            }
                        } catch (e: Exception) {
                            if (connectDeadline.hasTimedOut()) throw java.net.SocketTimeoutException("connect timed out")
                            throw e
                        }
                    } finally {
                        connectDeadline.cancel()
                    }
                    link.socket = s
                    if (links[link.mac] !== link || !isActive) throw java.io.IOException("stale_generation")
                    link.isConnected = true
                    retryAttempt = 0
                    publishState(link, PeerConnectionState.Synchronizing(link.generation))
                    link.v2Job?.cancel()
                    link.v2Job = launch { runV2UpgradeSupervisor(adapter, link, device) }
                    val outbound = Channel<ByteArray>(64)
                    link.outboundQueue = outbound
                    val writerJob = launch {
                        try {
                            for (packet in outbound) {
                                s.outputStream.write(packet)
                                s.outputStream.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[${link.mac}] writer failed: ${e.message}")
                            s.runCatching { close() }
                        } finally {
                            outbound.close()
                        }
                    }
                    val connectedAtElapsedMs = peerSupervisor.elapsedRealtime()
                    Log.d(TAG, "[$peerMac] connected (name='$peerName')")

                    val svc = ServiceManager.getService()
                    val announcement = if (svc?.holdsAirPods() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                    if (!enqueue(link, announcement)) throw java.io.IOException("writer_queue_unavailable")
                    if (!enqueue(link, CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)) {
                        throw java.io.IOException("status_sync_queue_unavailable")
                    }

                    // Keep-alive: send a harmless REQUEST_CONNECTION_STATUS ping
                    // periodically so Android's BT power-management doesn't drop
                    // the ACL link between handover events.
                    val keepAliveJob = launch {
                        while (isActive) {
                            delay(KEEPALIVE_INTERVAL_MS)
                            if (!link.isConnected) break
                            try {
                                if (!enqueue(link, CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)) {
                                    s.runCatching { close() }
                                    break
                                }
                                Log.d(TAG, "[$peerMac] keep-alive ping sent @ ${System.currentTimeMillis()}")
                            } catch (e: Exception) {
                                // W5: distinguish "ping failed (link dying)" from "process
                                // frozen, ping never attempted" — the latter shows as a GAP
                                // in these timestamps with no failure line.
                                Log.w(TAG, "[$peerMac] keep-alive ping FAILED: ${e.message}")
                                s.runCatching { close() }
                                break
                            }
                            val lastStatus = CrossDevice.peerStatusReceivedAt(peerMac) ?: connectedAtElapsedMs
                            if (peerSupervisor.elapsedRealtime() - lastStatus > 45_000L) {
                                Log.w(TAG, "[$peerMac] heartbeat status expired; closing stale RFCOMM link")
                                s.runCatching { close() }
                                break
                            }
                        }
                    }

                    val buf = ByteArray(1024)
                    while (isActive && links[link.mac] === link && link.socket === s) {
                        val n = try {
                            s.inputStream.read(buf)
                        } catch (e: Exception) {
                            Log.d(TAG, "[$peerMac] read error (peer disconnected): ${e.message}")
                            break
                        }
                        if (n == -1) break
                        if (!isActive || links[link.mac] !== link || link.socket !== s) break
                        CrossDevice.processPacket(buf.copyOf(n), link.mac)
                    }

                    keepAliveJob.cancel()
                    link.v2Socket?.runCatching { close() }
                    link.v2Job?.cancelAndJoin()
                    link.v2Job = null
                    outbound.close()
                    writerJob.cancel()
                    if (link.outboundQueue === outbound) link.outboundQueue = null
                    s.runCatching { close() }
                    if (link.socket === s) link.socket = null
                    link.isConnected = false
                    CrossDevice.onPeerTransportDisconnected(link.mac)
                    lastError = PeerConnectionError.REMOTE_UNAVAILABLE
                    Log.d(TAG, "[${link.mac}] disconnected, will retry")
                } catch (e: CancellationException) {
                    link.v2Job?.cancel()
                    link.v2Socket?.runCatching { close() }
                    link.v2Job = null
                    link.socket?.runCatching { close() }
                    link.outboundQueue?.close()
                    link.outboundQueue = null
                    link.socket = null
                    link.isConnected = false
                    CrossDevice.onPeerTransportDisconnected(link.mac)
                    lastError = PeerConnectionError.IO_FAILURE
                    throw e
                } catch (e: Exception) {
                    link.v2Job?.cancel()
                    link.v2Socket?.runCatching { close() }
                    link.v2Job = null
                    val bondState = try {
                        when (adapter.getRemoteDevice(link.mac).bondState) {
                            BluetoothDevice.BOND_BONDED -> "BONDED"
                            BluetoothDevice.BOND_BONDING -> "BONDING"
                            BluetoothDevice.BOND_NONE -> "NONE"
                            else -> "?"
                        }
                    } catch (_: Exception) { "unknown" }
                    Log.d(
                        TAG,
                        "[${link.mac}] connect failed [${e.javaClass.simpleName}: ${e.message}] " +
                            "bond=$bondState adapterEnabled=${adapter.isEnabled} " +
                            "discovering=${adapter.isDiscovering}, generation=${link.generation}"
                    )
                    link.socket?.runCatching { close() }
                    link.outboundQueue?.close()
                    link.outboundQueue = null
                    link.socket = null
                    link.isConnected = false
                    CrossDevice.onPeerTransportDisconnected(link.mac)
                    lastError = when {
                        e is SecurityException -> PeerConnectionError.PERMISSION_REQUIRED
                        !adapter.isEnabled -> PeerConnectionError.ADAPTER_UNAVAILABLE
                        e.message?.contains("timed out", ignoreCase = true) == true -> PeerConnectionError.CONNECT_TIMEOUT
                        e.message?.contains("invalid", ignoreCase = true) == true -> PeerConnectionError.PROTOCOL_INVALID
                        else -> PeerConnectionError.REMOTE_UNAVAILABLE
                    }
                    if (PeerRetryPolicy.requiresUserCorrection(lastError)) {
                        publishState(link, PeerConnectionState.WaitingForPermission, lastError)
                        link.bluetoothWake.receive()
                        continue
                    }
                }
                if (isActive) {
                    if (!adapter.isEnabled) {
                        publishState(link, PeerConnectionState.WaitingForBluetooth)
                        continue
                    }
                    val localAudioActive = ServiceManager.getService()?.isConnected() == true
                    val jitter = Random.nextDouble(0.8, 1.2000001)
                    val sleepMs = PeerRetryPolicy.delayMs(retryAttempt, localAudioActive, jitter)
                    retryAttempt++
                    val retryAt = peerSupervisor.elapsedRealtime() + sleepMs
                    publishState(link, PeerConnectionState.Backoff(retryAttempt, retryAt), lastError)
                    Log.d(TAG, "[${link.mac}] retry in ${sleepMs}ms (attempt=$retryAttempt, generation=${link.generation})")
                    val bj = launch { delay(sleepMs) }
                    link.backoffJob = bj
                    bj.join()
                    link.backoffJob = null
                }
            }
        }
    }

    private fun publishState(
        link: PeerLink,
        state: PeerConnectionState,
        error: PeerConnectionError? = null,
    ) {
        if (links[link.mac] !== link) return
        val previous = mutableStates.value[link.mac]
        mutableStates.update { it + (link.mac to state) }
        mutableErrors.update { current ->
            if (error == null) current - link.mac else current + (link.mac to error)
        }
        if (previous != state) LidAutoconnectDiagnostics.logConnectionEvent("peer", "state", mapOf(
            "state" to state.javaClass.simpleName,
            "error" to error?.name,
            "generation" to link.generation,
            "protocol" to (state as? PeerConnectionState.Ready)?.protocolVersion,
            "attempt" to (state as? PeerConnectionState.Backoff)?.attempt,
        ))
    }

    @SuppressLint("MissingPermission")
    private suspend fun runV2UpgradeSupervisor(adapter: BluetoothAdapter, link: PeerLink, device: BluetoothDevice) {
        while (kotlin.coroutines.coroutineContext.isActive && links[link.mac] === link && link.isConnected) {
            try {
                runV2Session(adapter, link, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "[${link.mac}] v2 synchronization failed (${e.javaClass.simpleName}); legacy path remains available")
            }
            if (!kotlin.coroutines.coroutineContext.isActive || links[link.mac] !== link || !link.isConnected) break
            delay(V2_RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runV2Session(adapter: BluetoothAdapter, link: PeerLink, device: BluetoothDevice) = kotlinx.coroutines.coroutineScope {
        if (!adapter.isEnabled || !link.isConnected) throw CancellationException("peer_link_not_ready")
        val localInstall = CrossDevice.installationId ?: throw java.io.IOException("local_identity_missing")
        val localSession = CrossDevice.processSessionId ?: throw java.io.IOException("local_session_missing")
        val socket = peerSupervisor.createSocket(adapter, link.mac, RFCOMM_V2_UUID)
        link.v2Socket = socket
        val connectDeadline = PeerConnectDeadline(
            scope = this,
            timeoutMs = CONNECT_TIMEOUT_MS,
            isCurrent = { links[link.mac] === link && link.isConnected && link.v2Socket === socket },
            isConnected = { socket.isConnected },
            closeSocket = { socket.close() },
        )
        try {
            try {
                peerSupervisor.connectSerialized {
                    if (!link.isConnected || link.v2Socket !== socket || connectDeadline.hasTimedOut()) throw java.io.IOException("v2_connect_cancelled")
                    socket.connect()
                    if (connectDeadline.hasTimedOut()) throw java.net.SocketTimeoutException("v2 connect timed out")
                    if (!socket.isConnected || link.v2Socket !== socket) throw java.io.IOException("v2_connect_failed")
                }
            } catch (e: Exception) {
                if (connectDeadline.hasTimedOut()) throw java.net.SocketTimeoutException("v2 connect timed out")
                throw e
            } finally {
                connectDeadline.cancel()
            }
        } catch (e: Exception) {
            socket.runCatching { close() }
            if (link.v2Socket === socket) link.v2Socket = null
            throw e
        }
        if (!CrossDevice.addV2Socket(link.mac, socket)) {
            socket.runCatching { close() }
            if (link.v2Socket === socket) link.v2Socket = null
            throw java.io.IOException("duplicate_v2_link")
        }

        val connectionId = java.util.UUID.randomUUID().toString()
        val decoder = PeerV2Frame.Decoder()
        val queue = Channel<ByteArray>(64)
        link.v2Queue = queue
        val writer = launch(Dispatchers.IO) {
            try {
                for (bytes in queue) {
                    socket.outputStream.write(bytes)
                    socket.outputStream.flush()
                }
            } catch (_: Exception) {
                socket.runCatching { close() }
            }
        }
        var remoteInstall: String? = null
        var remoteSession: String? = null
        var remoteAcquisitionCapable = false
        var initialSnapshot = false
        val initialPong = AtomicBoolean(false)
        val heartbeatState = PeerHeartbeat()
        val running = AtomicBoolean(true)
        val heartbeat = launch {
            while (running.get() && link.isConnected && link.v2Socket === socket) {
                delay(V2_HEARTBEAT_MS)
                when (val action = heartbeatState.onInterval()) {
                    is PeerHeartbeatAction.SendPing -> if (!queue.trySend(PeerV2Message.encode(
                        PeerV2Frame.Type.PING, mapOf("nonce" to action.nonce)).encode()).isSuccess) {
                        socket.runCatching { close() }
                    }
                    PeerHeartbeatAction.AwaitResponse -> Unit
                    PeerHeartbeatAction.TimedOut -> { socket.runCatching { close() }; break }
                }
            }
        }
        val handshakeWatchdog = launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (!initialPong.get()) socket.runCatching { close() }
        }
        try {
            val hello = PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
                "installationId" to localInstall,
                "sessionId" to localSession,
                "capabilities" to listOf("acquire-v1", "snapshot"),
                "initiatorId" to localInstall,
                "connectionId" to connectionId,
            ))
            check(queue.trySend(hello.encode()).isSuccess) { "v2_writer_unavailable" }
            val bytes = ByteArray(1024)
            while (running.get() && link.isConnected && link.v2Socket === socket) {
                val count = socket.inputStream.read(bytes)
                if (count < 0) break
                for (frame in decoder.feed(bytes.copyOf(count))) {
                    val message = PeerV2Message.decode(frame)
                    when (message.type) {
                        PeerV2Frame.Type.HELLO -> {
                            if (remoteInstall != null) throw IllegalArgumentException("duplicate_hello")
                            val fields = message.fields
                            require(fields["initiatorId"] == localInstall && fields["connectionId"] == connectionId) { "v2_connection_identity_mismatch" }
                            remoteInstall = fields["installationId"] as String
                            remoteSession = fields["sessionId"] as String
                            remoteAcquisitionCapable = "acquire-v1" in (fields["capabilities"] as List<*>).filterIsInstance<String>()
                            require(CrossDevice.registerV2Connection(link.mac, socket,
                                PeerConnectionKey(localInstall, connectionId))) { "duplicate_v2_link_loser" }
                            CrossDevice.bindV2PeerIdentity(link.mac, remoteInstall!!)
                        }
                        PeerV2Frame.Type.SNAPSHOT -> {
                            val session = remoteSession ?: throw IllegalArgumentException("hello_required")
                            require(message.fields["sessionId"] == session) { "v2_session_mismatch" }
                            require(CrossDevice.observeRemoteV2Snapshot(link.mac, session, message.fields)) {
                                "v2_snapshot_not_authoritative"
                            }
                            if (!initialSnapshot) {
                                initialSnapshot = true
                                val localSnapshot = PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, CrossDevice.currentV2Snapshot())
                                check(queue.trySend(localSnapshot.encode()).isSuccess) { "v2_writer_unavailable" }
                                val nonce = heartbeatState.startPing() ?: error("initial_ping_already_pending")
                                check(queue.trySend(PeerV2Message.encode(PeerV2Frame.Type.PING, mapOf("nonce" to nonce)).encode()).isSuccess) { "v2_writer_unavailable" }
                            }
                        }
                        PeerV2Frame.Type.PING -> {
                            val nonce = message.fields["nonce"] as String
                            val payload = mapOf("nonce" to nonce, "snapshot" to CrossDevice.currentV2Snapshot())
                            check(queue.trySend(PeerV2Message.encode(PeerV2Frame.Type.PONG, payload).encode()).isSuccess) { "v2_writer_unavailable" }
                        }
                        PeerV2Frame.Type.PONG -> {
                            val nonce = message.fields["nonce"] as String
                            if (heartbeatState.acknowledge(nonce)) {
                                val snapshot = stringKeyedMap(message.fields["snapshot"])
                                val session = remoteSession ?: throw IllegalArgumentException("hello_required")
                                require(snapshot["sessionId"] == session) { "v2_session_mismatch" }
                                require(CrossDevice.observeRemoteV2Snapshot(link.mac, session, snapshot)) {
                                    "v2_pong_snapshot_not_authoritative"
                                }
                                initialPong.set(true)
                                CrossDevice.markV2PeerReady(link.mac, session, socket, remoteAcquisitionCapable)
                                publishState(link, PeerConnectionState.Ready(link.generation, 2, session,
                                    snapshot["revision"] as Long))
                            }
                        }
                        PeerV2Frame.Type.ACQUIRE_REQUEST, PeerV2Frame.Type.ACQUIRE_GRANT,
                        PeerV2Frame.Type.ACQUIRE_DENY, PeerV2Frame.Type.ACQUIRE_CANCEL,
                        PeerV2Frame.Type.ACQUIRE_START, PeerV2Frame.Type.ACQUIRE_START_ACK -> {
                            require(remoteAcquisitionCapable) { "peer_missing_acquire_v1" }
                            val session = remoteSession ?: throw IllegalArgumentException("hello_required")
                            CrossDevice.handleV2AcquisitionMessage(link.mac, session, message.type, message.fields)
                        }
                        else -> throw IllegalArgumentException("unsupported_v2_message")
                    }
                }
            }
        } finally {
            running.set(false)
            handshakeWatchdog.cancel()
            heartbeat.cancel()
            queue.close()
            writer.cancel()
            if (link.v2Queue === queue) link.v2Queue = null
            socket.runCatching { close() }
            if (link.v2Socket === socket) link.v2Socket = null
            CrossDevice.removeV2Socket(link.mac, socket)
            if (link.legacyReady && links[link.mac] === link) {
                publishState(link, PeerConnectionState.Ready(link.generation, 1, null, null))
            }
        }
    }

    private fun stringKeyedMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("invalid_v2_snapshot")
        return map.entries.associate { (key, item) ->
            (key as? String ?: throw IllegalArgumentException("invalid_v2_snapshot_key")) to item
        }
    }

    /** Update state after a current legacy status response, not after a successful write. */
    fun onLegacySnapshotReceived(mac: String) {
        val link = links[mac.uppercase()] ?: return
        if (!link.isConnected) return
        link.legacyReady = true
        if (link.v2Socket?.isConnected == true &&
            mutableStates.value[link.mac] is PeerConnectionState.Ready &&
            (mutableStates.value[link.mac] as PeerConnectionState.Ready).protocolVersion == 2) return
        publishState(
            link,
            PeerConnectionState.Ready(
                generation = link.generation,
                protocolVersion = 1,
                sessionId = null,
                snapshotRevision = null,
            ),
        )
    }

    /** Bluetooth-off closes pending/live client sockets; Bluetooth-on wakes each owned loop. */
    fun onBluetoothStateChanged(enabled: Boolean) {
        links.values.forEach { link ->
            if (enabled) {
                link.bluetoothWake.trySend(Unit)
                retryNow(link.mac)
            } else {
                link.socket?.runCatching { close() }
                link.backoffJob?.cancel()
                link.heldHeadsetDelayJob?.cancel()
                publishState(link, PeerConnectionState.WaitingForBluetooth)
            }
        }
    }

    /**
     * The server accepted an inbound connection from [mac]. Wake our owned loop so it
     * can observe that path and defer a redundant outbound attempt for [durationMs].
     */
    fun pauseLink(mac: String, durationMs: Long = 5 * 60 * 1000L) {
        val link = links[mac.uppercase()] ?: return
        if (link.isConnected) return   // already connected outbound — nothing to suspend
        link.inboundRecheckMs = durationMs.coerceAtLeast(1_000L)
        Log.d(TAG, "[${mac}] inbound connection accepted; deferring redundant dial for ${link.inboundRecheckMs / 1000}s")
        // Wake the owned loop; it observes the accepted inbound socket itself and
        // installs a cancellable wait that Retry can interrupt.
        link.backoffJob?.cancel()
        link.heldHeadsetDelayJob?.cancel()
    }

    /**
     * Cancel the backoff delay and retry immediately for the link to [mac] (or all
     * links if [mac] is null). Call this when ACL_CONNECTED fires for a peer, so we
     * don't wait out the backoff before attempting the RFCOMM layer on top.
     */
    fun retryNow(mac: String? = null) {
        val targets = if (mac != null) listOfNotNull(links[mac.uppercase()]) else links.values.toList()
        targets.forEach { link ->
            if (link.isConnected) return@forEach
            Log.d(TAG, "[${link.mac}] retryNow: short-circuiting backoff (ACL came up)")
            link.reachabilityProbeRequested = true
            link.bluetoothWake.trySend(Unit)
            link.backoffJob?.cancel()
            link.heldHeadsetDelayJob?.cancel()
        }
    }

    /**
     * Request one immediate connection attempt for a user-visible Retry action.
     * Unlike passive recovery, this may run while the local phone holds the
     * AirPods, and is therefore never invoked automatically.
     */
    @SuppressLint("MissingPermission")
    fun requestManualReconnect(adapter: BluetoothAdapter, mac: String) {
        val normalizedMac = mac.uppercase()
        val link = links[normalizedMac]
        if (link?.isConnected == true || CrossDevice.isConnectedTo(normalizedMac)) return
        if (link == null) {
            // This phone may be the server-only side of role election. A manual
            // retry must still work from its warning banner, so create one
            // user-requested client link. The remote's normal client will pause
            // itself after accepting this inbound link, avoiding a lasting duel.
            Log.d(TAG, "[$mac] manual reconnect requested from server-only side")
            startLink(adapter, mac, manualHeldHeadsetAttemptRequested = true)
            return
        }
        link.manualHeldHeadsetAttemptRequested = true
        link.bluetoothWake.trySend(Unit)
        Log.d(TAG, "[$mac] manual reconnect requested")
        link.backoffJob?.cancel()
        link.heldHeadsetDelayJob?.cancel()
    }

    /** Send to a single peer's link. No-op if that peer has no live socket. */
    fun send(mac: String, data: ByteArray) {
        if (data.isEmpty()) return
        links[mac.uppercase()]?.let { enqueue(it, data) }
    }

    /** Broadcast to every live client link. */
    fun sendAll(data: ByteArray) {
        if (data.isEmpty()) return
        links.values.forEach { link ->
            enqueue(link, data)
        }
    }

    /** Send an EPH2 frame through the ready session's sole writer. */
    internal fun sendV2Frame(mac: String, frame: PeerV2Frame.Frame): Boolean {
        val link = links[mac.uppercase()] ?: return false
        val ready = mutableStates.value[link.mac] as? PeerConnectionState.Ready ?: return false
        if (ready.protocolVersion != 2 || link.v2Socket?.isConnected != true) return false
        val queue = link.v2Queue ?: return false
        if (queue.trySend(frame.encode()).isSuccess) return true
        link.v2Socket?.runCatching { close() }
        return false
    }

    private fun enqueue(link: PeerLink, data: ByteArray): Boolean {
        val queue = link.outboundQueue ?: return false
        val result = queue.trySend(data.copyOf())
        if (result.isSuccess) return true
        Log.w(TAG, "[${link.mac}] outbound queue unavailable; closing unhealthy link")
        link.socket?.runCatching { close() }
        return false
    }

    /** Stop the link to a single peer (per-peer remove, no global teardown). */
    fun stop(mac: String) {
        val normalizedMac = mac.uppercase()
        val link = links.remove(normalizedMac) ?: return
        peerSupervisor.stop(link)
        mutableStates.update { it - normalizedMac }
        mutableErrors.update { it - normalizedMac }
        CrossDevice.onPeerTransportDisconnected(normalizedMac)
    }

    /** Stop every client link. */
    fun stop() {
        links.keys.toList().forEach { stop(it) }
    }
}
