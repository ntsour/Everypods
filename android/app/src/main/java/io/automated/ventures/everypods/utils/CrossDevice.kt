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
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.utils.peer.PeerHeartbeat
import io.automated.ventures.everypods.utils.peer.PeerHeartbeatAction
import io.automated.ventures.everypods.utils.peer.ElapsedClock
import io.automated.ventures.everypods.utils.peer.PeerAudioUse
import io.automated.ventures.everypods.utils.peer.PeerObservation
import io.automated.ventures.everypods.utils.peer.PeerOwnership
import io.automated.ventures.everypods.utils.peer.PeerOwnershipStore
import io.automated.ventures.everypods.utils.peer.PeerConnectionKey
import io.automated.ventures.everypods.utils.peer.PeerConnectionArbitration
import io.automated.ventures.everypods.utils.peer.PeerIdentityStore
import io.automated.ventures.everypods.utils.peer.PassiveAcquisitionCoordinator
import io.automated.ventures.everypods.utils.peer.PeerAcquisitionProtocol
import io.automated.ventures.everypods.utils.peer.PeerSnapshotRevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import android.os.SystemClock
import io.automated.ventures.everypods.utils.peer.PeerV2Frame
import io.automated.ventures.everypods.utils.peer.PeerV2Message
import java.io.InputStream
import java.io.OutputStream

enum class CrossDevicePackets(val packet: ByteArray) {
    AIRPODS_CONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x01)),
    AIRPODS_DISCONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
    REQUEST_DISCONNECT(byteArrayOf(0x00, 0x02, 0x00, 0x00)),
    REQUEST_BATTERY_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x01)),
    REQUEST_ANC_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x02)),
    REQUEST_CONNECTION_STATUS(byteArrayOf(0x00, 0x02, 0x00, 0x03)),
    REQUEST_HANDOVER(byteArrayOf(0x00, 0x02, 0x00, 0x04)),
    AIRPODS_DATA_HEADER(byteArrayOf(0x00, 0x04, 0x00, 0x01)),
    // Windows → Android: whether a call/meeting is actively using AirPods on Windows.
    WINDOWS_AUDIO_ACTIVE(byteArrayOf(0x00, 0x03, 0x00, 0x01)),
    WINDOWS_AUDIO_IDLE(byteArrayOf(0x00, 0x03, 0x00, 0x00)),
    // Courtesy-filter handshake (holder-authoritative). The requester broadcasts
    // REQUEST_TAKEOVER; each peer replies COURTESY_DENY only if IT currently holds
    // the AirPods and its own toggle forbids interruption in its current state,
    // otherwise COURTESY_GRANT. The requester latches "did anyone deny?" — N-device
    // safe (only the real holder denies; any deny blocks; no deny → pull).
    REQUEST_TAKEOVER(byteArrayOf(0x00, 0x02, 0x00, 0x05)),
    COURTESY_GRANT(byteArrayOf(0x00, 0x06, 0x00, 0x00)),
    COURTESY_DENY(byteArrayOf(0x00, 0x06, 0x00, 0x01)),
}

object CrossDevice {
    private const val TAG = "CrossDevice"
    private val UUID_CROSS_DEVICE = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")
    private val UUID_CROSS_DEVICE_V2 = UUID.fromString("97ce0b4d-8e2f-4a6d-b6cd-d69d362e75ac")

    // Keep-alive ping interval for server-side sockets, mirroring CrossDeviceClient.
    // Server-only role (when the peer's MAC sorts lower than ours) has no app traffic
    // between handovers, so without this Android's BT power-management can drop the ACL.
    private const val SERVER_KEEPALIVE_INTERVAL_MS = 20_000L

    /**
     * W5 diagnostic: current power-management state of the EveryPods process. Sampled at
     * each keep-alive tick so we can correlate link drops with Doze entering or with the
     * app NOT being battery-optimization-exempt (OEM standby freeze). Cheap; logs only.
     */
    private fun powerStateDiag(): String {
        val ctx = ServiceManager.getService()?.applicationContext ?: return "[power:?]"
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            "[doze=${pm.isDeviceIdleMode} battExempt=${pm.isIgnoringBatteryOptimizations(ctx.packageName)}]"
        } catch (_: Exception) { "[power:err]" }
    }

    var isEnabled: Boolean = false

    /** Set of configured peer MACs. Loaded from `cross_device_peers` on [init];
     *  migrates from the legacy `cross_device_peer_mac` single-string key on first
     *  load. The RFCOMM server only accepts inbound connections from this set. */
    var configuredPeers: Set<String> = emptySet()
        internal set
    @Volatile var installationId: String? = null
        private set
    @Volatile var processSessionId: String? = null
        private set

    /** Which peer MACs currently hold the AirPods. `isAvailable` is a computed
     *  view of this set. Each entry is attributed to the exact source MAC that sent
     *  REQUEST_HANDOVER / REQUEST_DISCONNECT / AIRPODS_CONNECTED / AIRPODS_DATA. */
    val holders: MutableSet<String> = CopyOnWriteArraySet()

    /**
     * Test-only in-ear override so unit tests can exercise accept/ignore handover
     * without mocking [AirPodsService] (which loads native `bluetooth_socket`).
     * `null` = consult ServiceManager; non-null forces the in-ear answer.
     */
    @Volatile
    internal var inEarOverrideForTesting: Boolean? = null

    /** True when any peer holds the AirPods (i.e. [holders] is non-empty).
     *  Only the setter for false (holders.clear) is used internally now. */
    var isAvailable: Boolean
        get() = holders.isNotEmpty()
        set(value) { if (value) holders.addAll(configuredPeers) else holders.clear() }

    /** True when Windows has reported an active audio session (call/meeting) on the AirPods endpoint. */
    var peerAudioActive: Boolean = false

    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()

    /** True when at least one RFCOMM client is connected to our server, or our client is connected. */
    val isServerClientConnected: Boolean get() = clientSockets.isNotEmpty() || v2ClientSockets.isNotEmpty()
    val isPeerConnected: Boolean get() = isServerClientConnected || CrossDeviceClient.isConnected

    /** True when the specific peer [mac] is reachable — either via an inbound server socket
     *  or via our outbound client link. Used by the UI to show per-peer connection status. */
    fun isConnectedTo(mac: String): Boolean =
        clientSockets.any { it.remoteDevice.address.equals(mac, ignoreCase = true) } ||
        v2ClientSockets.any { it.remoteDevice.address.equals(mac, ignoreCase = true) } ||
        CrossDeviceClient.isConnected(mac)

    /** Passive acquisition needs a fresh explicit RELEASED/IDLE report for the selected headset. */
    fun hasFreshPeerOwnershipSnapshot(): Boolean {
        val targetAddress = ServiceManager.getService()?.macAddress?.takeIf { it.isNotBlank() } ?: return false
        val targetId = runCatching { PeerIdentityStore.headsetIdFromAddress(targetAddress) }.getOrNull() ?: return false
        // Legacy status has no headset/session/revision; unknown and media/call use are not permission.
        if (!peerOwnershipStore.allPeersRelease(targetId)) return false
        return configuredPeers.all { v2AcquisitionCapable.contains(it.uppercase()) } && peerOwnershipStore.observations().all { (peer, observation) ->
            v2ReadySessions[peer] == observation.sessionId
        } && configuredPeers.all { peer -> v2ReadySessions.containsKey(peer.uppercase()) }
    }

    fun observeV2PeerSnapshot(
        peerId: String,
        headsetId: String?,
        sessionId: String,
        revision: Long,
        ownership: PeerOwnership,
        audioUse: PeerAudioUse,
        receivedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = peerOwnershipStore.observe(
        PeerObservation(peerId, headsetId, sessionId, revision, receivedAtElapsedMs, ownership, audioUse, protocolVersion = 2),
    )

    fun allPeersRelease(headsetId: String): Boolean = peerOwnershipStore.allPeersRelease(headsetId)

    internal fun currentV2Snapshot(): Map<String, Any?> = localSnapshot()

    internal fun bindV2PeerIdentity(mac: String, installationId: String): String? {
        val context = ServiceManager.getService()?.applicationContext ?: return null
        val previous = PeerIdentityStore.peerInstallationId(context, mac)
        if (previous != installationId) {
            PeerIdentityStore.bindPeerAddress(context, mac, installationId)
            peerOwnershipStore.invalidate(mac)
        }
        return installationId
    }

    internal fun addV2Socket(mac: String, socket: BluetoothSocket): Boolean {
        v2ClientSockets.addIfAbsent(socket)
        return true
    }

    /** Register identity only after HELLO and close duplicate links symmetrically. */
    internal fun registerV2Connection(mac: String, socket: BluetoothSocket, key: PeerConnectionKey): Boolean {
        v2ConnectionKeys[socket] = key
        val candidates = v2ClientSockets.filter { candidate ->
            runCatching { candidate.remoteDevice.address.equals(mac, true) }.getOrDefault(false) &&
                v2ConnectionKeys.containsKey(candidate)
        }
        val winner = PeerConnectionArbitration.preferred(candidates.mapNotNull(v2ConnectionKeys::get)) ?: return false
        candidates.filter { v2ConnectionKeys[it] != winner }.forEach { loser ->
            loser.runCatching { close() }
        }
        return v2ConnectionKeys[socket] == winner
    }

    internal fun removeV2Socket(mac: String, socket: BluetoothSocket) {
        v2ClientSockets.remove(socket)
        v2ConnectionKeys.remove(socket)
        v2InboundQueues.remove(socket)?.close()
        if (v2ReadySockets.remove(mac.uppercase(), socket)) {
            v2ReadySessions.remove(mac.uppercase())
            v2AcquisitionCapable.remove(mac.uppercase())
            peerOwnershipStore.invalidate(mac)
        }
        if (v2ClientSockets.none { runCatching { it.remoteDevice.address.equals(mac, true) }.getOrDefault(false) }) {
            v2ReadySessions.remove(mac.uppercase())
            v2ReadySockets.remove(mac.uppercase())
            v2AcquisitionCapable.remove(mac.uppercase())
            peerOwnershipStore.invalidate(mac)
            Log.d(TAG, "[$mac] v2 snapshot invalidated after final v2 link loss")
        }
    }

    /** Remove every transport path and authority record for a peer being unpaired. */
    fun removePeerTransport(mac: String) {
        val normalized = mac.uppercase()
        cancelPassiveAcquisition()
        CrossDeviceClient.stop(normalized)
        clientSockets.filter { socket ->
            runCatching { socket.remoteDevice.address.equals(mac, ignoreCase = true) }.getOrDefault(false)
        }.forEach { socket ->
            clientSockets.remove(socket)
            socket.runCatching { close() }
        }
        v2ClientSockets.filter { socket ->
            runCatching { socket.remoteDevice.address.equals(mac, ignoreCase = true) }.getOrDefault(false)
        }.forEach { socket ->
            socket.runCatching { close() }
            removeV2Socket(mac, socket)
        }
        holders.removeIf { it.equals(mac, ignoreCase = true) }
        ownershipObservations.remove(normalized)
        peerOwnershipStore.invalidate(mac)
        v2ReadySessions.remove(normalized)
        v2ReadySockets.remove(normalized)
        v2AcquisitionCapable.remove(normalized)
        Log.i(TAG, "[$mac] peer removed: inbound/outbound transports and ownership state cleared")
    }

    internal fun markV2PeerReady(mac: String, sessionId: String, acquisitionCapable: Boolean = false) {
        v2ReadySessions[mac.uppercase()] = sessionId
        if (acquisitionCapable) v2AcquisitionCapable.add(mac.uppercase()) else v2AcquisitionCapable.remove(mac.uppercase())
    }

    internal fun markV2PeerReady(mac: String, sessionId: String, socket: BluetoothSocket, acquisitionCapable: Boolean = false) {
        v2ReadySessions[mac.uppercase()] = sessionId
        v2ReadySockets[mac.uppercase()] = socket
        if (acquisitionCapable) v2AcquisitionCapable.add(mac.uppercase()) else v2AcquisitionCapable.remove(mac.uppercase())
    }

    internal fun observeRemoteV2Snapshot(mac: String, helloSessionId: String, snapshot: Map<String, Any?>): Boolean {
        return observeRemoteSnapshot(mac, helloSessionId, snapshot)
    }

    fun setPassiveAcquisitionReadyListener(listener: ((PassiveAcquisitionCoordinator.Event.ReadyToConnect) -> Unit)?) {
        passiveAcquisitionReady = listener
    }

    /** Start a short-lived, all-peer grant round for one current lid-open intent. */
    fun beginPassiveAcquisition(lastOwnerEligible: Boolean, enabled: Boolean, lidOpen: Boolean): Boolean {
        fun blocked(reason: String): Boolean {
            Log.d(TAG, "passive acquisition blocked reason=$reason")
            return false
        }
        val service = ServiceManager.getService() ?: return false
        val targetAddress = service.macAddress.takeIf { it.isNotBlank() } ?: return false
        val snapshot = runCatching { localSnapshot() }.getOrNull() ?: return false
        val headsetId = snapshot["headsetId"] as? String ?: return false
        val existing = passiveAcquisitionCoordinator?.currentIntent()
        if (existing != null && existing.phase in setOf(PassiveAcquisitionCoordinator.Phase.PREPARING,
                PassiveAcquisitionCoordinator.Phase.STARTING, PassiveAcquisitionCoordinator.Phase.IN_PROGRESS)) {
            if (existing.headsetId == headsetId &&
                SystemClock.elapsedRealtime() < existing.expiresAtElapsedMs) return true
            // Cancel/release only a pre-command round. If the headset command was
            // already issued, the coordinator keeps it BUSY until real ownership
            // evidence resolves the asynchronous OS attempt.
            cancelPassiveAcquisition()
        }
        val membersByMac = configuredInstallationIds() ?: return blocked("peer_identity_missing")
        val localId = installationId ?: return false
        val membership = membersByMac.values.toSet() + localId
        if (membership.size != configuredPeers.size + 1) return false
        val peers = passivePeerStatuses(headsetId, membersByMac) ?: return blocked("peer_snapshot_or_session_missing")
        val localOwnership = snapshot["ownership"] as? String
        Log.d(TAG, "passive acquisition local=$localOwnership peers=${peers.values}")
        val frames = passiveAcquisitionProtocol?.begin(
            headsetId = headsetId,
            membership = membership,
            localUnowned = localOwnership == "released",
            lastOwnerEligible = lastOwnerEligible,
            lidOpen = lidOpen,
            enabled = enabled,
            localSnapshotRevision = snapshot["revision"] as? Long ?: return false,
            peers = peers,
        ).orEmpty()
        if (frames.isEmpty()) return blocked("coordinator_rejected_or_unavailable")
        sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, snapshot))
        dispatchAcquisitionFrames(frames)
        return true
    }

    fun refreshPassiveOwnership() {
        sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.PING,
            mapOf("nonce" to java.util.UUID.randomUUID().toString())))
    }

    fun cancelPassiveAcquisition() {
        dispatchAcquisitionFrames(passiveAcquisitionProtocol?.cancel().orEmpty())
    }

    /** Resolve an in-flight requester reservation only after actual headset ownership is observed. */
    fun confirmPassiveAcquisitionIfHeld() {
        val service = ServiceManager.getService() ?: return
        if (!service.holdsAirPods()) return
        val coordinator = passiveAcquisitionCoordinator ?: return
        val current = coordinator.currentIntent()
        if (current?.phase == PassiveAcquisitionCoordinator.Phase.IN_PROGRESS) {
            sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
            coordinator.requesterResolve(current.requestId, true)
            sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
        }
    }

    fun authorizePassiveAcquisition(
        event: PassiveAcquisitionCoordinator.Event.ReadyToConnect,
        lidOpen: Boolean,
        enabled: Boolean,
    ): Boolean {
        val service = ServiceManager.getService() ?: return false
        val target = service.macAddress.takeIf { it.isNotBlank() } ?: return false
        val headsetId = runCatching { PeerIdentityStore.headsetIdFromAddress(target) }.getOrNull() ?: return false
        val current = runCatching { localSnapshot() }.getOrNull() ?: return false
        if (current["ownership"] != "released" || current["audioUse"] != "idle") return false
        val membershipByMac = configuredInstallationIds() ?: return false
        val localId = installationId ?: return false
        val membership = membershipByMac.values.toSet() + localId
        if (membership.size != configuredPeers.size + 1) return false
        val peers = passivePeerStatuses(headsetId, membershipByMac) ?: return false
        val authorized = passiveAcquisitionCoordinator?.authorizeConnect(
            requestId = event.requestId,
            generationAtCall = event.generation,
            lidOpen = lidOpen,
            enabled = enabled,
            headsetId = headsetId,
            membership = membership,
            peers = peers,
        ) == true
        val currentIntent = passiveAcquisitionCoordinator?.currentIntent()
        if (!authorized && currentIntent?.requestId == event.requestId &&
            currentIntent.phase == PassiveAcquisitionCoordinator.Phase.CANCELLED) {
            dispatchAcquisitionFrames(passiveAcquisitionProtocol?.cancel().orEmpty())
        }
        return authorized
    }

    internal fun handleV2AcquisitionMessage(
        mac: String,
        remoteSessionId: String,
        type: PeerV2Frame.Type,
        fields: Map<String, Any?>,
    ) {
        val context = ServiceManager.getService()?.applicationContext ?: return
        val remoteId = PeerIdentityStore.peerInstallationId(context, mac) ?: return
        val localId = installationId ?: return
        val membersByMac = configuredInstallationIds() ?: return
        val membership = membersByMac.values.toSet() + localId
        val target = ServiceManager.getService()?.macAddress?.takeIf { it.isNotBlank() }
            ?.let { runCatching { PeerIdentityStore.headsetIdFromAddress(it) }.getOrNull() }
        val localStatus = target?.let { localPeerStatus(it) }
        val peerStatuses = if (target != null) passivePeerStatuses(target, membersByMac) else null
        val currentRevisions = if (localStatus != null && peerStatuses != null) {
            peerStatuses.mapValues { it.value.revision } + (localStatus.installationId to localStatus.revision)
        } else null
        val frame = PeerV2Message.encode(type, fields)
        val result = passiveAcquisitionProtocol?.receive(remoteId, remoteSessionId, frame,
            localStatus, membership, currentRevisions) ?: return
        Log.d(TAG, "passive acquisition received=$type replies=${result.replies.map { PeerV2Message.decode(it).let { message -> message.type to message.fields["reason"] } }} ready=${result.readyToConnect != null}")
        dispatchAcquisitionFrames(result.replies)
        result.readyToConnect?.let { passiveAcquisitionReady?.invoke(it) }
    }

    private fun configuredInstallationIds(): Map<String, String>? {
        val context = ServiceManager.getService()?.applicationContext ?: return null
        val result = linkedMapOf<String, String>()
        configuredPeers.forEach { mac ->
            val id = PeerIdentityStore.peerInstallationId(context, mac) ?: return null
            if (id == installationId || result.values.contains(id)) return null
            result[mac.uppercase()] = id
        }
        return result
    }

    private fun localPeerStatus(headsetId: String): PassiveAcquisitionCoordinator.PeerStatus? {
        val localId = installationId ?: return null
        val snapshot = runCatching { localSnapshot() }.getOrNull() ?: return null
        val ownership = when (snapshot["ownership"]) {
            "held" -> PeerOwnership.HELD
            "released" -> PeerOwnership.RELEASED
            else -> PeerOwnership.UNKNOWN
        }
        val audioUse = when (snapshot["audioUse"]) {
            "idle" -> PeerAudioUse.IDLE
            "media" -> PeerAudioUse.MEDIA
            "call" -> PeerAudioUse.CALL
            else -> PeerAudioUse.UNKNOWN
        }
        val session = processSessionId ?: return null
        return PassiveAcquisitionCoordinator.PeerStatus(localId, session,
            snapshot["revision"] as? Long ?: return null, 2, true, headsetId, ownership, audioUse)
    }

    private fun passivePeerStatuses(
        headsetId: String,
        membersByMac: Map<String, String>,
    ): Map<String, PassiveAcquisitionCoordinator.PeerStatus>? {
        val observations = peerOwnershipStore.observations()
        val result = linkedMapOf<String, PassiveAcquisitionCoordinator.PeerStatus>()
        membersByMac.forEach { (mac, id) ->
            val observation = observations[mac] ?: run {
                Log.d(TAG, "passive acquisition missing snapshot peer=$mac")
                return null
            }
            if (v2ReadySessions[mac] != observation.sessionId) {
                Log.d(TAG, "passive acquisition session not ready peer=$mac")
                return null
            }
            result[id] = PassiveAcquisitionCoordinator.PeerStatus(
                id, observation.sessionId, observation.revision, 2, true, observation.headsetId,
                observation.ownership, observation.audioUse,
            )
        }
        if (result.size != membersByMac.size || result.values.any { it.headsetId != headsetId }) return null
        return result
    }

    private fun dispatchAcquisitionFrames(frames: List<PeerV2Frame.Frame>) {
        val localId = installationId ?: return
        val membersByMac = configuredInstallationIds()
        val byId = membersByMac?.entries?.associate { it.value to it.key }.orEmpty()
        frames.forEach { frame ->
            val message = runCatching { PeerV2Message.decode(frame) }.getOrNull() ?: return@forEach
            val targetIds = when (frame.type) {
                PeerV2Frame.Type.ACQUIRE_REQUEST, PeerV2Frame.Type.ACQUIRE_CANCEL -> byId.keys
                PeerV2Frame.Type.ACQUIRE_START -> setOfNotNull(message.fields["grantorId"] as? String)
                PeerV2Frame.Type.ACQUIRE_GRANT, PeerV2Frame.Type.ACQUIRE_DENY,
                PeerV2Frame.Type.ACQUIRE_START_ACK -> setOfNotNull(message.fields["requesterId"] as? String)
                else -> emptySet()
            }
            targetIds.filter { it != localId }.forEach targetLoop@{ id ->
                val mac = byId[id] ?: return@targetLoop
                if (!CrossDeviceClient.sendV2Frame(mac, frame)) sendInboundV2Frame(mac, frame)
            }
        }
    }

    private fun sendInboundV2Frame(mac: String, frame: PeerV2Frame.Frame) {
        val socket = v2ReadySockets[mac.uppercase()] ?: return
        val queue = v2InboundQueues[socket] ?: return
        if (!queue.trySend(frame.encode()).isSuccess) socket.runCatching { close() }
    }

    private fun sendV2ToConfiguredPeers(frame: PeerV2Frame.Frame) {
        configuredPeers.forEach { mac ->
            if (!CrossDeviceClient.sendV2Frame(mac, frame)) sendInboundV2Frame(mac, frame)
        }
    }

    fun peerStatusReceivedAt(mac: String): Long? = ownershipObservations[mac.uppercase()]?.atElapsedMs

    /** A lost last link invalidates its status, while a remaining link keeps it observable. */
    fun onPeerTransportDisconnected(mac: String) {
        if (!isConnectedTo(mac)) {
            ownershipObservations.remove(mac.uppercase())
            ownershipObservations.remove(mac.lowercase())
            peerOwnershipStore.invalidate(mac)
            Log.d(TAG, "[$mac] peer ownership is UNKNOWN until a fresh status arrives")
        }
    }

    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val v2ClientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    private val v2ConnectionKeys = java.util.concurrent.ConcurrentHashMap<BluetoothSocket, PeerConnectionKey>()
    private val v2InboundQueues = ConcurrentHashMap<BluetoothSocket, Channel<ByteArray>>()
    private val v2ReadySessions = ConcurrentHashMap<String, String>()
    private val v2ReadySockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val v2AcquisitionCapable = ConcurrentHashMap.newKeySet<String>()
    private class ListenerEndpoint(val name: String, val uuid: UUID) {
        val generation = AtomicLong(0L)
        @Volatile var socket: BluetoothServerSocket? = null
        @Volatile var job: Job? = null
        @Volatile var scope: CoroutineScope? = null
        @Volatile var running: Boolean = false
        @Volatile var permissionRequired: Boolean = false
        val acceptedSockets = ConcurrentHashMap.newKeySet<BluetoothSocket>()
        val ownerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }
    private val legacyEndpoint = ListenerEndpoint("EveryPodsCrossDevice", UUID_CROSS_DEVICE)
    private val v2Endpoint = ListenerEndpoint("EveryPodsCrossDeviceV2", UUID_CROSS_DEVICE_V2)
    private data class OwnershipObservation(val holding: Boolean, val atElapsedMs: Long)
    private val ownershipObservations = ConcurrentHashMap<String, OwnershipObservation>()
    private val peerOwnershipStore = PeerOwnershipStore(ElapsedClock { SystemClock.elapsedRealtime() })
    @Volatile private var passiveAcquisitionCoordinator: PassiveAcquisitionCoordinator? = null
    @Volatile private var passiveAcquisitionProtocol: PeerAcquisitionProtocol? = null
    @Volatile private var passiveAcquisitionReady: ((PassiveAcquisitionCoordinator.Event.ReadyToConnect) -> Unit)? = null
    @Volatile private var passiveCoordinatorInstallationId: String? = null
    @Volatile private var passiveCoordinatorSessionId: String? = null
    private val v2SnapshotRevision = PeerSnapshotRevision()
    private val isServerRunning: Boolean get() = legacyEndpoint.running || v2Endpoint.running

    private fun startEndpoint(adapter: android.bluetooth.BluetoothAdapter, endpoint: ListenerEndpoint) {
        val generation = endpoint.generation.incrementAndGet()
        val peersScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        endpoint.scope = peersScope
        endpoint.running = true
        endpoint.job = endpoint.ownerScope.launch {
            var listener: BluetoothServerSocket? = null
            try {
                val activeListener = adapter.listenUsingInsecureRfcommWithServiceRecord(endpoint.name, endpoint.uuid)
                listener = activeListener
                if (endpoint.generation.get() != generation || !isEnabled || !adapter.isEnabled) {
                    activeListener.close()
                    return@launch
                }
                endpoint.socket = activeListener
                Log.d(TAG, "RFCOMM ${endpoint.name} listening generation=$generation")
                while (isActive && endpoint.generation.get() == generation) {
                    val socket = try { activeListener.accept() } catch (e: IOException) {
                        if (endpoint.generation.get() == generation) Log.d(TAG, "${endpoint.name} stopped: ${e.message}")
                        break
                    }
                    if (endpoint.generation.get() != generation) { socket.runCatching { close() }; break }
                    val addr = runCatching { socket.remoteDevice.address }.getOrNull()
                    val context = ServiceManager.getService()?.applicationContext
                    val connections = context?.let { io.automated.ventures.everypods.relay.DeviceConnectionStore(it) }
                    val hasCapability = addr != null && connections != null && (
                        connections.has(addr, io.automated.ventures.everypods.relay.DeviceConnectionType.BT_HANDOVER) ||
                            connections.has(addr, io.automated.ventures.everypods.relay.DeviceConnectionType.WIFI_RELAY) ||
                            addr.uppercase() in connections.pendingRevocations())
                    if (addr == null || configuredPeers.none { it.equals(addr, ignoreCase = true) } || !hasCapability) {
                        Log.w(TAG, "Rejecting unconfigured ${endpoint.name} peer")
                        socket.runCatching { close() }
                        continue
                    }
                    val registered = if (endpoint === legacyEndpoint) clientSockets.add(socket) else addV2Socket(addr, socket)
                    if (!registered) { socket.runCatching { close() }; continue }
                    endpoint.acceptedSockets.add(socket)
                    if (endpoint.generation.get() != generation || !endpoint.running) {
                        endpoint.acceptedSockets.remove(socket)
                        socket.runCatching { close() }
                        continue
                    }
                    if (endpoint === legacyEndpoint) CrossDeviceClient.pauseLink(addr)
                    peersScope.launch {
                        try {
                            if (endpoint === legacyEndpoint) handleClientConnection(socket, peersScope)
                            else handleV2ClientConnection(socket, addr, generation, endpoint)
                        } finally {
                            endpoint.acceptedSockets.remove(socket)
                        }
                    }
                }
            } catch (e: IOException) {
                if (endpoint.generation.get() == generation) Log.e(TAG, "${endpoint.name} failed: ${e.message}")
            } catch (_: SecurityException) {
                endpoint.permissionRequired = true
                if (endpoint.generation.get() == generation) Log.w(TAG, "${endpoint.name} waiting for Bluetooth permission")
            } finally {
                listener?.runCatching { close() }
                peersScope.cancel()
                if (endpoint.generation.get() == generation) {
                    endpoint.socket = null
                    endpoint.job = null
                    endpoint.scope = null
                    endpoint.running = false
                }
            }
            if (endpoint.generation.get() == generation && isEnabled && adapter.isEnabled && !endpoint.permissionRequired) {
                delay(2_000L)
                startEndpoint(adapter, endpoint)
            }
        }
    }

    private fun stopEndpoint(endpoint: ListenerEndpoint) {
        endpoint.generation.incrementAndGet()
        endpoint.job?.cancel()
        endpoint.scope?.cancel()
        endpoint.socket?.runCatching { close() }
        endpoint.acceptedSockets.toList().forEach { it.runCatching { close() } }
        endpoint.acceptedSockets.clear()
        endpoint.job = null
        endpoint.scope = null
        endpoint.socket = null
        endpoint.running = false
    }

    private fun closeV2ClientSockets() {
        v2ClientSockets.toList().forEach { socket ->
            val address = runCatching { socket.remoteDevice.address }.getOrNull()
            socket.runCatching { close() }
            if (address != null) removeV2Socket(address, socket) else {
                v2ClientSockets.remove(socket)
                v2ConnectionKeys.remove(socket)
            }
        }
    }

    private suspend fun handleV2ClientConnection(
        socket: BluetoothSocket,
        address: String,
        generation: Long,
        endpoint: ListenerEndpoint,
    ) = coroutineScope {
        val decoder = PeerV2Frame.Decoder()
        val input = socket.inputStream
        val outbound = Channel<ByteArray>(64)
        v2InboundQueues[socket] = outbound
        val writer = launch(Dispatchers.IO) {
            try {
                for (bytes in outbound) {
                    socket.outputStream.write(bytes)
                    socket.outputStream.flush()
                }
            } catch (_: Exception) { socket.runCatching { close() } }
        }
        var helloSeen = false
        var remoteSessionId: String? = null
        var remoteAcquisitionCapable = false
        val syncComplete = AtomicBoolean(false)
        val heartbeatState = PeerHeartbeat()
        val syncWatchdog = launch {
            delay(5_000L)
            if (!syncComplete.get()) socket.runCatching { close() }
        }
        val heartbeat = launch {
            while (isActive && endpoint.generation.get() == generation &&
                runCatching { socket.isConnected }.getOrDefault(false)) {
                delay(10_000L)
                when (val action = heartbeatState.onInterval()) {
                    is PeerHeartbeatAction.SendPing -> if (!outbound.trySend(PeerV2Message.encode(
                        PeerV2Frame.Type.PING, mapOf("nonce" to action.nonce)).encode()).isSuccess) {
                        socket.runCatching { close() }
                    }
                    PeerHeartbeatAction.AwaitResponse -> Unit
                    PeerHeartbeatAction.TimedOut -> {
                        Log.w(TAG, "[$address] v2 heartbeat response missed twice")
                        socket.runCatching { close() }
                    }
                }
            }
        }
        try {
            val chunk = ByteArray(1024)
            while (endpoint.generation.get() == generation && isEnabled) {
                val count = input.read(chunk)
                if (count < 0) break
                for (frame in decoder.feed(chunk.copyOf(count))) {
                    val message = PeerV2Message.decode(frame)
                    if (!helloSeen) {
                        require(message.type == PeerV2Frame.Type.HELLO) { "hello_required" }
                        require(configuredPeers.any { it.equals(address, ignoreCase = true) }) { "unconfigured_peer" }
                        val remote = message.fields
                        val localInstall = installationId ?: error("identity_unavailable")
                        val localSession = processSessionId ?: error("session_unavailable")
                        val remoteInstall = remote.getValue("installationId") as String
                        remoteSessionId = remote.getValue("sessionId") as String
                        remoteAcquisitionCapable = "acquire-v1" in (remote.getValue("capabilities") as List<*>).filterIsInstance<String>()
                        require(remote.getValue("initiatorId") == remoteInstall) { "invalid_initiator_identity" }
                        val context = ServiceManager.getService()?.applicationContext ?: error("service_unavailable")
                        val oldIdentity = PeerIdentityStore.peerInstallationId(context, address)
                        if (oldIdentity != remoteInstall) {
                            PeerIdentityStore.bindPeerAddress(context, address, remoteInstall)
                            peerOwnershipStore.invalidate(address)
                            Log.i(TAG, "Configured peer protocol identity synchronized for $address")
                        }
                        val initiatorId = remote.getValue("initiatorId") as String
                        val connectionId = remote.getValue("connectionId") as String
                        require(registerV2Connection(address, socket, PeerConnectionKey(initiatorId, connectionId))) {
                            "duplicate_v2_link_loser"
                        }
                        queueV2Frame(outbound, PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
                            "installationId" to localInstall,
                            "sessionId" to localSession,
                            "capabilities" to listOf("acquire-v1", "snapshot"),
                            "initiatorId" to initiatorId,
                            "connectionId" to connectionId,
                        )))
                        queueV2Frame(outbound, PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
                        val nonce = heartbeatState.startPing() ?: error("initial_ping_already_pending")
                        queueV2Frame(outbound, PeerV2Message.encode(PeerV2Frame.Type.PING, mapOf("nonce" to nonce)))
                        helloSeen = true
                    } else when (message.type) {
                        PeerV2Frame.Type.SNAPSHOT -> {
                            require(observeRemoteSnapshot(address, remoteSessionId, message.fields)) {
                                "snapshot_not_authoritative"
                            }
                        }
                        PeerV2Frame.Type.PING -> {
                            val snapshot = localSnapshot()
                            queueV2Frame(outbound, PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, snapshot))
                            queueV2Frame(outbound, PeerV2Message.encode(PeerV2Frame.Type.PONG, mapOf(
                                "nonce" to message.fields.getValue("nonce"), "snapshot" to snapshot,
                            )))
                        }
                        PeerV2Frame.Type.PONG -> {
                            val nonce = message.fields.getValue("nonce") as String
                            if (!heartbeatState.acknowledge(nonce)) continue
                            require(observeRemoteSnapshot(address, remoteSessionId,
                                stringKeyedMap(message.fields.getValue("snapshot")))) {
                                "pong_snapshot_not_authoritative"
                            }
                            syncComplete.set(true)
                            remoteSessionId?.let { markV2PeerReady(address, it, socket, remoteAcquisitionCapable) }
                            syncWatchdog.cancel()
                        }
                        PeerV2Frame.Type.ACQUIRE_REQUEST, PeerV2Frame.Type.ACQUIRE_GRANT,
                        PeerV2Frame.Type.ACQUIRE_DENY, PeerV2Frame.Type.ACQUIRE_CANCEL,
                        PeerV2Frame.Type.ACQUIRE_START, PeerV2Frame.Type.ACQUIRE_START_ACK -> {
                            require(remoteAcquisitionCapable) { "peer_missing_acquire_v1" }
                            val session = remoteSessionId ?: error("hello_required")
                            handleV2AcquisitionMessage(address, session, message.type, message.fields)
                        }
                        else -> error("unsupported_v2_message")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "v2 peer session ended (${e.javaClass.simpleName})")
        } finally {
            syncWatchdog.cancel()
            heartbeat.cancel()
            outbound.close()
            writer.cancel()
            socket.runCatching { close() }
            removeV2Socket(address, socket)
        }
    }

    private fun queueV2Frame(queue: Channel<ByteArray>, frame: PeerV2Frame.Frame) {
        check(queue.trySend(frame.encode()).isSuccess) { "v2_writer_unavailable" }
    }

    private fun localSnapshot(): Map<String, Any?> {
        val service = ServiceManager.getService()
        val targetAddress = service?.macAddress?.takeIf { it.isNotBlank() }
        val targetId = targetAddress?.let { runCatching { PeerIdentityStore.headsetIdFromAddress(it) }.getOrNull() }
        val held = service?.holdsAirPods()
        val audioUse = service?.peerAudioUse() ?: PeerAudioUse.UNKNOWN
        val ownership = when (held) { true -> "held"; false -> "released"; null -> "unknown" }
        val state = mapOf(
            "headsetId" to targetId,
            "ownership" to ownership,
            "audioUse" to audioUse.name.lowercase(),
            "lastOwnerId" to null,
            "lastOwnerRevision" to null,
            "acquisition" to acquisitionSnapshot(),
        )
        return state + mapOf(
            "sessionId" to (processSessionId ?: error("session_unavailable")),
            "revision" to v2SnapshotRevision.forState(state),
        )
    }

    private fun acquisitionSnapshot(): Map<String, Any?>? {
        val requester = installationId ?: return null
        val intent = passiveAcquisitionCoordinator?.currentIntent()
        if (intent != null && intent.phase in setOf(PassiveAcquisitionCoordinator.Phase.PREPARING,
                PassiveAcquisitionCoordinator.Phase.STARTING, PassiveAcquisitionCoordinator.Phase.IN_PROGRESS)) {
            return mapOf("requestId" to intent.requestId, "requesterId" to requester,
                "phase" to if (intent.phase == PassiveAcquisitionCoordinator.Phase.IN_PROGRESS) "in_progress" else "preparing")
        }
        val reservation = passiveAcquisitionCoordinator?.currentReservation() ?: return null
        if (reservation.phase !in setOf(PassiveAcquisitionCoordinator.Phase.PREPARING,
                PassiveAcquisitionCoordinator.Phase.STARTING, PassiveAcquisitionCoordinator.Phase.IN_PROGRESS)) return null
        return mapOf("requestId" to reservation.requestId, "requesterId" to reservation.requesterId,
            "phase" to if (reservation.phase == PassiveAcquisitionCoordinator.Phase.IN_PROGRESS) "in_progress" else "preparing")
    }

    private fun stringKeyedMap(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: error("invalid_snapshot")
        return map.entries.associate { (key, entry) ->
            (key as? String ?: error("invalid_snapshot_key")) to entry
        }
    }

    private fun observeRemoteSnapshot(address: String, helloSessionId: String?, snapshot: Map<String, Any?>): Boolean {
        val expectedInstallId = PeerIdentityStore.peerInstallationId(
            ServiceManager.getService()?.applicationContext ?: return false, address,
        ) ?: return false
        val remoteSession = snapshot["sessionId"] as? String ?: return false
        if (helloSessionId == null || remoteSession != helloSessionId || expectedInstallId.isBlank()) return false
        val ownership = when (snapshot["ownership"]) {
            "held" -> PeerOwnership.HELD
            "released" -> PeerOwnership.RELEASED
            else -> PeerOwnership.UNKNOWN
        }
        val audioUse = when (snapshot["audioUse"]) {
            "idle" -> PeerAudioUse.IDLE
            "media" -> PeerAudioUse.MEDIA
            "call" -> PeerAudioUse.CALL
            else -> PeerAudioUse.UNKNOWN
        }
        // HELLO's installation identity is bound to this configured MAC and the
        // snapshot session must remain the exact process session from that HELLO.
        val accepted = observeV2PeerSnapshot(
            address, snapshot["headsetId"] as? String, remoteSession,
            snapshot["revision"] as? Long ?: return false, ownership, audioUse,
        )
        if (!accepted) return false
        val localHeadset = ServiceManager.getService()?.macAddress?.takeIf { it.isNotBlank() }
            ?.let { PeerIdentityStore.headsetIdFromAddress(it) }
        if (localHeadset != null && snapshot["headsetId"] == localHeadset) {
            if (ownership == PeerOwnership.RELEASED) holders.removeIf { it.equals(address, true) }
            if (ownership == PeerOwnership.HELD) holders.add(address.uppercase())
        }
        val acquisition = snapshot["acquisition"] as? Map<*, *>
        val requestId = acquisition?.get("requestId") as? String
        val requesterId = acquisition?.get("requesterId") as? String
        if (ownership == PeerOwnership.HELD && requesterId == expectedInstallId && requestId != null) {
            passiveAcquisitionCoordinator?.participantResolve(requestId, true)
        }
        return true
    }

    private fun writeV2Frame(output: OutputStream, frame: PeerV2Frame.Frame) {
        val bytes = frame.encode()
        output.write(bytes)
        output.flush()
    }

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val nextPeers = loadAndMigratePeers(prefs)
        val previousPeers = configuredPeers
        val identity = PeerIdentityStore.load(context)
        val previousNormalized = previousPeers.mapTo(mutableSetOf()) { it.uppercase() }
        val nextNormalized = nextPeers.mapTo(mutableSetOf()) { it.uppercase() }
        val membershipChanged = previousNormalized != nextNormalized
        val removedPeers = previousPeers.filter { old -> nextPeers.none { it.equals(old, ignoreCase = true) } }
        val addedPeers = nextPeers.filter { next -> previousPeers.none { it.equals(next, ignoreCase = true) } }
        val sessionChanged = passiveCoordinatorSessionId != null && passiveCoordinatorSessionId != identity.sessionId
        val installationChanged = passiveCoordinatorInstallationId != null &&
            passiveCoordinatorInstallationId != identity.installationId
        if (passiveAcquisitionCoordinator != null && (membershipChanged || sessionChanged || installationChanged)) {
            // Cancel only the local requester intent. The coordinator keeps any
            // participant IN_PROGRESS reservation, which cannot be released until
            // actual headset ownership or a definite failed-attempt signal arrives.
            cancelPassiveAcquisition()
        }
        // `init` can be reached through settings migration and service re-entry as
        // well as the explicit Remove button. Close inbound and outbound channels
        // while the old membership is still available for cancellation dispatch.
        removedPeers.forEach(::removePeerTransport)
        configuredPeers = nextPeers
        peerOwnershipStore.configure(configuredPeers)
        // Newly configured peers must start as UNKNOWN even if a stale transport
        // happened to leave an observation under a differently-cased address.
        addedPeers.forEach(peerOwnershipStore::invalidate)
        installationId = identity.installationId
        processSessionId = identity.sessionId
        if (passiveAcquisitionCoordinator == null || passiveCoordinatorInstallationId != identity.installationId) {
            val clock = ElapsedClock { SystemClock.elapsedRealtime() }
            passiveAcquisitionCoordinator = PassiveAcquisitionCoordinator(identity.installationId, clock)
        }
        passiveCoordinatorInstallationId = identity.installationId
        passiveCoordinatorSessionId = identity.sessionId
        passiveAcquisitionProtocol = PeerAcquisitionProtocol(identity.installationId,
            { processSessionId ?: error("session_unavailable") }, passiveAcquisitionCoordinator!!)
        // Fresh installs: Handover ON by default. Persist missing key so UI and
        // service agree; never overwrite an explicit false.
        if (!prefs.contains("cross_device_enabled")) {
            prefs.edit().putBoolean("cross_device_enabled", true).apply()
        }
        val connections = io.automated.ventures.everypods.relay.DeviceConnectionStore(context)
        connections.migrate(configuredPeers)
        io.automated.ventures.everypods.relay.BtHandoverSync.start(context)
        // Saved capabilities keep coordination online even when automatic handover
        // is switched off; otherwise its online-only UI could never enable it again.
        isEnabled = prefs.getBoolean("cross_device_enabled", true) || configuredPeers.any {
            connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.BT_HANDOVER) ||
                connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.WIFI_RELAY) || it.uppercase() in connections.pendingRevocations()
        }
        if (!isEnabled) {
            Log.d(TAG, "Cross-device disabled by preference")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled, skipping server start")
            return
        }
        startServer(adapter)

        // Per-pair role election: client to peers whose name sorts higher than ours,
        // server-only for the rest. Reconcile the client links against that set in
        // one call so existing links aren't torn down (and re-added) needlessly.
        val clientPeers = configuredPeers.filter {
            (connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.BT_HANDOVER) ||
                connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.WIFI_RELAY) ||
                it.uppercase() in connections.pendingRevocations()) && shouldBeClientTo(adapter, it)
        }.toSet()
        CrossDeviceClient.start(adapter, clientPeers)
    }

    /**
     * Role-elect whether this device should run the RFCOMM CLIENT to [peerMac].
     *
     * Both Android devices in a pair run the server (Windows always connects as a
     * client). Without this election they would also each start a client to each
     * other on the same UUID, producing two RFCOMM channels between the same MAC
     * pair — Bluedroid frequently kills the older session, which is the root
     * cause of the "Connect failed (read ret: -1)" loop.
     *
     * Election by **Bluetooth device name**: the local MAC is unreadable on modern
     * Android (`adapter.address` is the 02:00:00:00:00:00 sentinel and the Secure
     * "bluetooth_address" setting is locked down), which made the old MAC-based
     * election fall through to "client-on" on BOTH devices — the very collision it
     * was meant to prevent. Device names, however, are always readable: each device
     * knows its own (`adapter.name`) and the peer's (`getRemoteDevice(peerMac).name`,
     * cached for a bonded device). Both devices compare the SAME ordered pair, so
     * `ownName < peerName` is true on exactly one of them.
     *
     * Fallback when names are unusable (null/blank, or equal — identical models):
     * SERVER-only. Two server-only devices means no handover coordination, but no
     * `read ret: -1` collision storm either — strictly safer than starting a client.
     *
     * Pure predicate (no side effects): the caller reconciles the elected client set
     * against the live links via [CrossDeviceClient.start].
     */
    @SuppressLint("MissingPermission")
    private fun shouldBeClientTo(adapter: android.bluetooth.BluetoothAdapter, peerMac: String): Boolean {
        val ownName = adapter.name
        val peerName = runCatching { adapter.getRemoteDevice(peerMac).name }.getOrNull()
        val client = electClient(ownName, peerName)
        Log.d(TAG, "Role election [$peerMac]: ${if (client) "CLIENT" else "SERVER-only"} (own='$ownName' peer='$peerName')")
        return client
    }

    /** Pure role-election predicate (extracted for testing). Returns true iff this
     *  device should be the CLIENT for the pair. SERVER-only when names are unusable
     *  (null/blank or equal): no client started, so no duplicate-channel collision. */
    internal fun electClient(ownName: String?, peerName: String?): Boolean {
        if (ownName.isNullOrBlank() || peerName.isNullOrBlank() || ownName.equals(peerName, ignoreCase = true)) {
            return false
        }
        return ownName.compareTo(peerName, ignoreCase = true) < 0
    }

    /**
     * Tear down and re-open the RFCOMM server. Called by the "Reconnect to peer"
     * button so the user has a way to recover when the server side is wedged
     * (e.g. accept() returned but the socket is stale).
     */
    @SuppressLint("MissingPermission")
    fun restartServer(context: Context) {
        Log.d(TAG, "restartServer: closing server socket and ${clientSockets.size} client socket(s)")
        invalidateServerGeneration()
        closeV2ClientSockets()
        val oldPeers = clientSockets.mapNotNull { socket ->
            val addr = runCatching { socket.remoteDevice.address }.getOrNull()
            socket.runCatching { close() }
            addr
        }
        clientSockets.clear()
        oldPeers.forEach(::onPeerTransportDisconnected)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "restartServer: adapter unavailable or disabled")
            return
        }
        startServer(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startServer(adapter: android.bluetooth.BluetoothAdapter) {
        // Re-entry from app/service initialization is the permission recheck point.
        legacyEndpoint.permissionRequired = false
        v2Endpoint.permissionRequired = false
        if (!legacyEndpoint.running) startEndpoint(adapter, legacyEndpoint)
        if (!v2Endpoint.running) startEndpoint(adapter, v2Endpoint)
    }

    private fun invalidateServerGeneration() {
        stopEndpoint(legacyEndpoint)
        stopEndpoint(v2Endpoint)
    }

    @SuppressLint("MissingPermission")
    fun onBluetoothStateChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            CrossDeviceClient.onBluetoothStateChanged(true)
            init(context.applicationContext)
            return
        }
        invalidateServerGeneration()
        closeV2ClientSockets()
        val oldPeers = clientSockets.mapNotNull { socket ->
            val addr = runCatching { socket.remoteDevice.address }.getOrNull()
            socket.runCatching { close() }
            addr
        }
        clientSockets.clear()
        oldPeers.forEach(::onPeerTransportDisconnected)
        CrossDeviceClient.onBluetoothStateChanged(false)
    }

    private fun handleClientConnection(socket: BluetoothSocket, clientScope: CoroutineScope) {
        val addr = socket.remoteDevice.address
        val ctx = ServiceManager.getService()?.applicationContext
        ctx?.sendBroadcast(
            Intent("io.automated.ventures.everypods.AIRPODS_CONNECTED_REMOTELY").setPackage(ctx.packageName)
        )
        // Tell only this new client our current AirPods state
        sendToSocket(
            socket,
            if (ServiceManager.getService()?.holdsAirPods() == true)
                CrossDevicePackets.AIRPODS_CONNECTED.packet
            else
                CrossDevicePackets.AIRPODS_DISCONNECTED.packet
        )

        // Server-side keep-alive: when this device is "server only" (role election
        // suppressed our client), nothing else generates app-layer traffic between
        // handovers and Android Doze will eventually drop the idle ACL. Pinging the
        // peer every 20 s keeps it alive. The peer's CrossDeviceClient already
        // replies to REQUEST_CONNECTION_STATUS, so no protocol change is needed.
        val connectedAtElapsedMs = SystemClock.elapsedRealtime()
        val keepAliveJob: Job = clientScope.launch {
            while (true) {
                delay(SERVER_KEEPALIVE_INTERVAL_MS)
                if (!clientSockets.contains(socket)) break
                val ok = runCatching {
                    socket.outputStream.write(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
                    socket.outputStream.flush()
                }.isSuccess

                if (!ok) {
                    // W5: a failed write means the link is dying. A GAP in these
                    // timestamps with no failure line means the process was frozen.
                    Log.w(TAG, "Server keep-alive ping FAILED → $addr (link dying)")
                    break
                }
                val lastStatus = peerStatusReceivedAt(addr) ?: connectedAtElapsedMs
                if (SystemClock.elapsedRealtime() - lastStatus > 45_000L) {
                    Log.w(TAG, "Server heartbeat status expired → $addr (closing stale link)")
                    socket.runCatching { close() }
                    break
                }
                Log.d(TAG, "Server keep-alive ping → $addr @ ${System.currentTimeMillis()} ${powerStateDiag()}")
            }
        }

        val buffer = ByteArray(1024)
        while (true) {
            val bytes = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                Log.d(TAG, "Client $addr read error (disconnected): ${e.message}")
                break
            }
            if (bytes == -1) break
            processPacket(buffer.copyOf(bytes), addr)
        }

        keepAliveJob.cancel()
        socket.runCatching { close() }
        clientSockets.remove(socket)
        onPeerTransportDisconnected(addr)
        Log.d(TAG, "Client $addr removed (${clientSockets.size} remaining)")

        // NOTE: deliberately do NOT touch `isAvailable` here. A dropped RFCOMM
        // coordination socket means we lost VISIBILITY of the peer (Android Doze
        // routinely kills this idle link — that's why the 20 s keep-alive exists,
        // and OEM process-killers do the same), NOT that the peer released the
        // AirPods. Flipping isAvailable=false used to make this device believe the
        // pods were free and race to grab them, causing an A2DP tug-of-war.
        // Ownership only changes on an explicit AIRPODS_DISCONNECTED packet from
        // the peer (handled in processPacket) or our own intentful takeover.
    }

    /**
     * Handle one inbound packet from [sourceMac]. Shared by both transport sides —
     * the server read loop ([handleClientConnection]) and the per-peer client links
     * ([CrossDeviceClient]) both route here, so the protocol lives in one place.
     *
     * Ownership is attributed to [sourceMac]: a peer announcing/relaying ownership is
     * added to [holders]; a peer announcing release is removed. Replies (status,
     * relayed bytes, courtesy verdict) are sent **back to [sourceMac]** via [sendTo],
     * not broadcast — so a courtesy verdict for one requester can't be mis-latched by
     * an unrelated peer (the 3-device correctness fix).
     */

    /**
     * Accept REQUEST_HANDOVER / REQUEST_DISCONNECT when a pod is in ear (or unknown).
     * Returns false only when we positively know pods are in the case.
     */
    internal fun shouldAcceptHandoverRequest(): Boolean {
        inEarOverrideForTesting?.let { return it }
        return ServiceManager.getService()?.isAnyPodInEar() != false
    }

    internal fun processPacket(raw: ByteArray, sourceMac: String, context: Context? = ServiceManager.getService()?.applicationContext) {
        if (raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) || raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet)) {
            val allowsHandover = context != null &&
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("cross_device_enabled", true) &&
                io.automated.ventures.everypods.relay.DeviceConnectionStore(context).handoverReady(sourceMac)
            if (!allowsHandover) {
                Log.i(TAG, "[$sourceMac] Ignoring handover request: no enabled BT handover connection")
                return
            }
        }
        Log.d(TAG, "[$sourceMac] received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                // Peer wants the AirPods — release them if we hold the connection.
                // Only eagerly mark the peer as holder when we *accept* the takeover
                // (pod in ear / unknown). Ignoring when pods are in the case must NOT
                // sticky-add the peer — that blocked lid-open autoconnect via peer_holding.
                val service = ServiceManager.getService()
                if (shouldAcceptHandoverRequest()) {
                    Log.d(TAG, "[$sourceMac] REQUEST_HANDOVER — releasing AirPods (eagerly marking peer as holder)")
                    holders.add(sourceMac)
                    service?.markPeerTakeoverAttempt()
                    service?.disconnectForCD()
                } else {
                    // Prefer check-in-ear-first: never add on ignore. Defensive remove
                    // covers any older path that added before the ignore branch.
                    holders.remove(sourceMac)
                    Log.d(TAG, "[$sourceMac] REQUEST_HANDOVER ignored — no pod in ear (pods in case)")
                }
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) -> {
                // Same accept-only eager mark as REQUEST_HANDOVER.
                val service = ServiceManager.getService()
                if (shouldAcceptHandoverRequest()) {
                    holders.add(sourceMac)
                    service?.markPeerTakeoverAttempt()
                    service?.disconnectForCD()
                } else {
                    holders.remove(sourceMac)
                    Log.d(TAG, "[$sourceMac] REQUEST_DISCONNECT ignored — no pod in ear (pods in case)")
                }
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                holders.add(sourceMac)
                ownershipObservations[sourceMac.uppercase()] =
                    OwnershipObservation(true, SystemClock.elapsedRealtime())
                CrossDeviceClient.onLegacySnapshotReceived(sourceMac)
                // Peer explicitly announced ownership — if we were expecting a takeover,
                // arm the peer-drop cooldown proactively (don't wait for ACL_DISCONNECTED).
                ServiceManager.getService()?.confirmPeerOwnership()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) -> {
                holders.remove(sourceMac)
                ownershipObservations[sourceMac.uppercase()] =
                    OwnershipObservation(false, SystemClock.elapsedRealtime())
                CrossDeviceClient.onLegacySnapshotReceived(sourceMac)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_BATTERY_BYTES.packet) -> {
                sendTo(sourceMac, batteryBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_ANC_BYTES.packet) -> {
                sendTo(sourceMac, ancBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet) -> {
                sendTo(
                    sourceMac,
                    if (ServiceManager.getService()?.holdsAirPods() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                )
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_ACTIVE.packet) -> {
                peerAudioActive = true
                Log.d(TAG, "[$sourceMac] Windows reports active audio session (call/meeting in progress)")
            }
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_IDLE.packet) -> {
                peerAudioActive = false
                Log.d(TAG, "[$sourceMac] Windows reports audio session idle")
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_TAKEOVER.packet) -> {
                // Older builds send REQUEST_TAKEOVER before taking over; always grant so
                // they can proceed. We no longer use this round-trip ourselves — requester
                // always wins (see design rationale in handover.md).
                sendTo(sourceMac, CrossDevicePackets.COURTESY_GRANT.packet)
            }
            raw.contentEquals(CrossDevicePackets.COURTESY_DENY.packet) -> {
                // No-op: veto mechanism removed. Log for diagnostics only.
                Log.d(TAG, "[$sourceMac] COURTESY_DENY received (veto removed — ignored)")
            }
            raw.contentEquals(CrossDevicePackets.COURTESY_GRANT.packet) -> {
                // No-op: veto mechanism removed.
            }
            raw.size >= 4 && raw.sliceArray(0..3)
                .contentEquals(CrossDevicePackets.AIRPODS_DATA_HEADER.packet) -> {
                holders.add(sourceMac)
                ownershipObservations[sourceMac.uppercase()] =
                    OwnershipObservation(true, SystemClock.elapsedRealtime())
                val deduplicated = deduplicateIfNeeded(raw)
                val payload = deduplicated.drop(CrossDevicePackets.AIRPODS_DATA_HEADER.packet.size).toByteArray()
                processRelayedPacket(payload)
            }
        }
    }

    private fun deduplicateIfNeeded(packet: ByteArray): ByteArray {
        if (packet.size % 2 == 0) {
            val half = packet.size / 2
            if (packet.sliceArray(0 until half).contentEquals(packet.sliceArray(half until packet.size))) {
                Log.d(TAG, "Deduplicated doubled packet")
                return packet.sliceArray(0 until half)
            }
        }
        return packet
    }

    private fun processRelayedPacket(payload: ByteArray) {
        val svc = ServiceManager.getService() ?: return
        when {
            svc.batteryNotification.isBatteryData(payload) -> {
                batteryBytes = payload
                svc.batteryNotification.setBattery(payload)
                svc.updateBattery()
                svc.sendBatteryBroadcast()
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                    svc.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    svc.sendBatteryNotification()
                }
            }
            svc.ancNotification.isANCData(payload) -> {
                ancBytes = payload
                svc.ancNotification.setStatus(payload)
                svc.sendANCBroadcast()
                svc.updateNoiseControlWidget()
            }
            svc.earDetectionNotification.isEarDetectionData(payload) -> {
                svc.earDetectionNotification.setStatus(payload)
                val inEar = svc.earDetectionNotification.status.contains(0x00.toByte())
                if (inEar) {
                    svc.applicationContext.sendBroadcast(
                        Intent("io.automated.ventures.everypods.cross_device_island").setPackage(svc.packageName)
                    )
                }
            }
        }
    }

    fun sendRemotePacket(data: ByteArray) {
        if (data.isEmpty()) return
        val dead = mutableListOf<BluetoothSocket>()
        for (socket in clientSockets) {
            sendToSocket(socket, data) { dead.add(socket) }
        }
        if (dead.isNotEmpty()) {
            clientSockets.removeAll(dead)
            dead.forEach { onPeerTransportDisconnected(it.remoteDevice.address) }
        }
    }

    private fun sendToSocket(socket: BluetoothSocket, data: ByteArray, onFail: (() -> Unit)? = null) {
        val hex = data.joinToString("") { "%02x".format(it) }
        try {
            socket.outputStream.write(data)
            socket.outputStream.flush()
            Log.d(TAG, "Sent to ${socket.remoteDevice.address}: $hex")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send to ${socket.remoteDevice.address}: ${e.message}")
            onFail?.invoke()
        }
    }

    /** Send a packet to one specific peer, regardless of which transport side reaches
     *  it: prefer an inbound server socket from that MAC, else our outbound client link. */
    fun sendTo(mac: String, data: ByteArray) {
        if (data.isEmpty()) return
        val socket = clientSockets.find { it.remoteDevice.address.equals(mac, ignoreCase = true) }
        if (socket != null) {
            sendToSocket(socket, data) {
                clientSockets.remove(socket)
                onPeerTransportDisconnected(mac)
            }
            return
        }
        CrossDeviceClient.send(mac, data)
    }

    /** Always grant: requester wins. Kept for backwards compat — old builds that
     *  send REQUEST_TAKEOVER receive GRANT so they can proceed. */
    @Suppress("unused")
    fun courtesyReplyPacket(): ByteArray = CrossDevicePackets.COURTESY_GRANT.packet

    /** Broadcast a packet to every peer: all inbound server clients **and** all
     *  outbound client links. The two transport sides are disjoint sets of peers
     *  (role election gives each pair exactly one direction), so this reaches all. */
    private fun broadcast(data: ByteArray) {
        sendRemotePacket(data)
        CrossDeviceClient.sendAll(data)
    }

    fun notifyConnected() {
        broadcast(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    }
    fun notifyDisconnected() {
        broadcast(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
    }

    fun close() {
        CrossDeviceClient.stop()
        invalidateServerGeneration()
        closeV2ClientSockets()
        clientSockets.forEach { it.runCatching { close() } }
        clientSockets.clear()
        holders.clear()
        ownershipObservations.clear()

        peerOwnershipStore.clear()
        // Peer selection is persistent configuration, not live connection state.
        // Preserve it so disabling handover does not remove the information needed
        // to enable it again without restarting the process.
        isEnabled = false
    }

    private fun loadAndMigratePeers(prefs: SharedPreferences): Set<String> {
        val peersJson = prefs.getString("cross_device_peers", null)
        if (peersJson != null) return parsePeerSet(peersJson)

        val legacyMac = prefs.getString("cross_device_peer_mac", null)
            ?: return emptySet()

        val set = setOf(legacyMac)
        prefs.edit()
            .putString("cross_device_peers", peerSetToJson(set))
            .remove("cross_device_peer_mac")
            .apply()
        Log.d(TAG, "Migrated cross_device_peer_mac → cross_device_peers: $legacyMac")
        return set
    }

    private fun parsePeerSet(json: String): Set<String> = buildSet {
        val arr = JSONArray(json)
        repeat(arr.length()) { add(arr.getString(it)) }
    }

    private fun peerSetToJson(peers: Set<String>): String =
        JSONArray(peers.toList()).toString()

    @Suppress("unused")
    internal fun resetForTesting() {
        io.automated.ventures.everypods.relay.BtHandoverSync.close()
        holders.clear()
        ownershipObservations.clear()
        peerOwnershipStore.clear()
        passiveAcquisitionCoordinator = null
        passiveAcquisitionProtocol = null
        passiveAcquisitionReady = null
        passiveCoordinatorInstallationId = null
        passiveCoordinatorSessionId = null
        installationId = null
        processSessionId = null
        configuredPeers = emptySet()
        peerOwnershipStore.configure(emptySet())
        isEnabled = false
        stopEndpoint(legacyEndpoint)
        stopEndpoint(v2Endpoint)
        peerAudioActive = false
        batteryBytes = byteArrayOf()
        ancBytes = byteArrayOf()
        inEarOverrideForTesting = null
    }

    @SuppressLint("MissingPermission")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("cross_device_enabled", enabled)
            .apply()
        val connections = io.automated.ventures.everypods.relay.DeviceConnectionStore(context)
        if (enabled || configuredPeers.any {
                connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.BT_HANDOVER) ||
                    connections.has(it, io.automated.ventures.everypods.relay.DeviceConnectionType.WIFI_RELAY) || it.uppercase() in connections.pendingRevocations()
            }) init(context)
        else close()
    }
}
