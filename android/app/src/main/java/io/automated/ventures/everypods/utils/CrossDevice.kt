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

import io.automated.ventures.everypods.utils.peer.PeerGattRecords
import io.automated.ventures.everypods.utils.peer.PeerGattClient
import io.automated.ventures.everypods.utils.peer.PeerGattServer
import io.automated.ventures.everypods.utils.peer.PeerLeChannel
import io.automated.ventures.everypods.utils.peer.PeerLeV2Links
import io.automated.ventures.everypods.utils.peer.PeerLeV2Session
import io.automated.ventures.everypods.utils.peer.PeerLinkElection
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.utils.peer.GrantDecision
import io.automated.ventures.everypods.utils.peer.GrantDenial
import io.automated.ventures.everypods.utils.peer.LegacyHandoverRequest
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

    // Keep-alive ping interval for server-side sockets.
    // Server-only role (when the peer's MAC sorts lower than ours) has no app traffic
    // between handovers, so without this Android's BT power-management can drop the ACL.
    private const val SERVER_KEEPALIVE_INTERVAL_MS = 20_000L

    /** Hourly STATS lines per peer (phase 0 of the quiet peer links plan). */
    private val linkStats by lazy { io.automated.ventures.everypods.utils.peer.PeerLinkStats(SystemClock.elapsedRealtime()) }

    /** Logs the STATS lines once an hour, from the link loops that already run (no timer of its own). */
    private fun logLinkStatsIfDue() {
        linkStats.takeIfDue(SystemClock.elapsedRealtime())?.forEach { Log.i(TAG, it) }
    }

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
    val isServerClientConnected: Boolean get() =
        clientSockets.isNotEmpty() || v2ClientSockets.isNotEmpty() || PeerGattServer.hasReadySession()
    val isPeerConnected: Boolean get() =
        isServerClientConnected || PeerGattClient.hasReadySession()

    /** True when the specific peer [mac] is reachable — either via an inbound server socket
     *  or via our outbound client link. Used by the UI to show per-peer connection status. */
    fun isConnectedTo(mac: String): Boolean =
        clientSockets.any { it.remoteDevice.address.equals(mac, ignoreCase = true) } ||
        v2ClientSockets.any { it.remoteDevice.address.equals(mac, ignoreCase = true) } ||
        PeerGattServer.isReady(mac) ||
        isAuthorizedGattClientLink(mac)

    /** A central link counts only for an authorized peer: before that it carries setup only (BtHandoverSync). */
    private fun isAuthorizedGattClientLink(mac: String): Boolean {
        if (!PeerGattClient.isReady(mac)) return false
        val ctx = gattContext ?: return false
        return io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(mac)
    }

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

    /** True when any peer's latest snapshot says it is playing media or on a call. */
    fun anyPeerUsingAudio(): Boolean = peerOwnershipStore.observations().values.any {
        it.audioUse == io.automated.ventures.everypods.utils.peer.PeerAudioUse.MEDIA ||
            it.audioUse == io.automated.ventures.everypods.utils.peer.PeerAudioUse.CALL
    }

    /** Fresh per-peer audio use (peer MAC, uppercase); peers without a fresh snapshot are absent. */
    fun freshPeerAudioUse(): Map<String, io.automated.ventures.everypods.utils.peer.PeerAudioUse> =
        peerOwnershipStore.freshAudioUses()

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
            v2IntentCapable.remove(mac.uppercase())
            peerOwnershipStore.invalidate(mac)
        }
        if (v2ClientSockets.none { runCatching { it.remoteDevice.address.equals(mac, true) }.getOrDefault(false) }) {
            v2ReadySessions.remove(mac.uppercase())
            v2ReadySockets.remove(mac.uppercase())
            v2AcquisitionCapable.remove(mac.uppercase())
            v2IntentCapable.remove(mac.uppercase())
            peerOwnershipStore.invalidate(mac)
            Log.d(TAG, "[$mac] v2 snapshot invalidated after final v2 link loss")
        }
    }

    /** Remove every transport path and authority record for a peer being unpaired. */
    fun removePeerTransport(mac: String) {
        val normalized = mac.uppercase()
        cancelPassiveAcquisition()
        PeerGattServer.forget(normalized)
        PeerGattClient.drop(normalized, "peer removed", 0L)
        if (leV2Links.linkDown(normalized)) PeerLeChannel.onLinkDown(normalized)
        forgetPeerInstallationId(normalized)
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
        v2IntentCapable.remove(normalized)
        Log.i(TAG, "[$mac] peer removed: inbound/outbound transports and ownership state cleared")
    }

    internal fun markV2PeerReady(mac: String, sessionId: String, acquisitionCapable: Boolean = false, intentCapable: Boolean = false) {
        v2ReadySessions[mac.uppercase()] = sessionId
        if (acquisitionCapable) v2AcquisitionCapable.add(mac.uppercase()) else v2AcquisitionCapable.remove(mac.uppercase())
        if (intentCapable) v2IntentCapable.add(mac.uppercase()) else v2IntentCapable.remove(mac.uppercase())
    }

    internal fun markV2PeerReady(mac: String, sessionId: String, socket: BluetoothSocket, acquisitionCapable: Boolean = false, intentCapable: Boolean = false) {
        v2ReadySessions[mac.uppercase()] = sessionId
        v2ReadySockets[mac.uppercase()] = socket
        if (acquisitionCapable) v2AcquisitionCapable.add(mac.uppercase()) else v2AcquisitionCapable.remove(mac.uppercase())
        if (intentCapable) v2IntentCapable.add(mac.uppercase()) else v2IntentCapable.remove(mac.uppercase())
        ServiceManager.getService()?.retryRecentRemoteCaseObservation()
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
            LidAutoconnectDiagnostics.logEvent("passive_blocked", mapOf("reason" to reason))
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
            allPeersSupportIntent = allSupportIntent(membersByMac.keys),
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
        sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
        dispatchAcquisitionFrames(frames)
        return if (frames.any { it.type == PeerV2Frame.Type.ACQUIRE_REQUEST }) true
            else blocked("coordinator_rejected_after_cancellation_replay")
    }

    fun refreshPassiveOwnership() {
        dispatchAcquisitionFrames(passiveAcquisitionProtocol?.replayCancellations().orEmpty())
        sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.PING,
            mapOf("nonce" to java.util.UUID.randomUUID().toString())))
    }

    fun cancelPassiveAcquisition() {
        passiveAcquisitionProtocol?.cancel()
        // authorizeConnect may already have made the cancellation transition.
        // Replay its terminal proof even when a second cancel is idempotent.
        dispatchAcquisitionFrames(passiveAcquisitionProtocol?.replayCancellations().orEmpty())
    }

    /** Snapshot for binding an audio attempt to the existing wire reservation. */
    fun pendingPassiveRequest(): Pair<String, String>? {
        val intent = passiveAcquisitionCoordinator?.currentIntent() ?: return null
        if (intent.phase !in setOf(PassiveAcquisitionCoordinator.Phase.PREPARING,
                PassiveAcquisitionCoordinator.Phase.STARTING,
                PassiveAcquisitionCoordinator.Phase.IN_PROGRESS) || intent.connectIssued ||
            SystemClock.elapsedRealtime() >= intent.expiresAtElapsedMs) return null
        return intent.requestId to intent.headsetId
    }

    /** Atomic identity check is performed inside the reservation coordinator. */
    fun cancelPassiveRequest(requestId: String, headsetId: String) {
        dispatchAcquisitionFrames(passiveAcquisitionProtocol?.cancelMatching(requestId, headsetId).orEmpty())
    }

    fun passiveCommandIsCurrent(event: PassiveAcquisitionCoordinator.Event.ReadyToConnect): Boolean =
        passiveAcquisitionCoordinator?.commandIsCurrent(event.requestId, event.generation) == true

    fun rejectPassiveCommand(event: PassiveAcquisitionCoordinator.Event.ReadyToConnect) {
        dispatchAcquisitionFrames(passiveAcquisitionProtocol?.rejectedCommand(event).orEmpty())
    }

    fun observePassiveA2dpState(state: Int) {
        val coordinator = passiveAcquisitionCoordinator ?: return
        when (state) {
            android.bluetooth.BluetoothProfile.STATE_CONNECTING -> coordinator.observeConnecting()
            android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                val frames = passiveAcquisitionProtocol?.terminalProfileFailure().orEmpty()
                dispatchAcquisitionFrames(frames)
            }
        }
    }

    /** Resolve an in-flight requester reservation only after actual headset ownership is observed. */
    fun confirmPassiveAcquisitionIfHeld() {
        val service = ServiceManager.getService() ?: return
        if (!service.hasConfirmedPassiveAudioConnection()) return
        val coordinator = passiveAcquisitionCoordinator ?: return
        val current = coordinator.currentIntent()
        if (current?.phase == PassiveAcquisitionCoordinator.Phase.IN_PROGRESS) {
            sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
            if (coordinator.requesterResolve(current.requestId, true)) {
                dispatchAcquisitionFrames(passiveAcquisitionProtocol?.replayCancellations().orEmpty())
            }
            sendV2ToConfiguredPeers(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, localSnapshot()))
        }
    }

    fun authorizePassiveAcquisition(
        event: PassiveAcquisitionCoordinator.Event.ReadyToConnect,
        lidOpen: Boolean,
        enabled: Boolean,
        issueCommand: Boolean = false,
    ): Boolean {
        fun invalidate(reason: String): Boolean {
            LidAutoconnectDiagnostics.logEvent("passive_authorization_invalid", mapOf("reason" to reason))
            // Matching cancellation cannot release a command already handed to Android.
            cancelPassiveRequest(event.requestId, event.headsetId)
            return false
        }
        val service = ServiceManager.getService() ?: return invalidate("service_unavailable")
        val target = service.macAddress.takeIf { it.isNotBlank() } ?: return invalidate("headset_unavailable")
        val headsetId = runCatching { PeerIdentityStore.headsetIdFromAddress(target) }.getOrNull()
            ?: return invalidate("headset_identity_unavailable")
        val current = runCatching { localSnapshot() }.getOrNull() ?: return invalidate("local_snapshot_unavailable")
        if (current["ownership"] != "released" || current["audioUse"] != "idle") return invalidate("local_not_idle")
        val membershipByMac = configuredInstallationIds() ?: return invalidate("membership_unavailable")
        val localId = installationId ?: return invalidate("local_identity_unavailable")
        val membership = membershipByMac.values.toSet() + localId
        if (membership.size != configuredPeers.size + 1) return invalidate("membership_changed")
        val peers = passivePeerStatuses(headsetId, membershipByMac) ?: return invalidate("peer_snapshot_unavailable")
        val authorized = passiveAcquisitionCoordinator?.authorizeConnect(
            requestId = event.requestId,
            generationAtCall = event.generation,
            lidOpen = lidOpen,
            enabled = enabled,
            headsetId = headsetId,
            membership = membership,
            peers = peers,
            localSnapshotRevision = current["revision"] as? Long ?: return invalidate("local_revision_unavailable"),
            issueCommand = issueCommand,
        ) == true
        val currentIntent = passiveAcquisitionCoordinator?.currentIntent()
        if (!authorized && currentIntent?.requestId == event.requestId &&
            currentIntent.phase == PassiveAcquisitionCoordinator.Phase.CANCELLED) {
            dispatchAcquisitionFrames(passiveAcquisitionProtocol?.replayCancellations().orEmpty())
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
            localStatus, membership, currentRevisions, peerSupportsIntent(mac)) ?: return
        Log.d(TAG, "passive acquisition received=$type replies=${result.replies.map { PeerV2Message.decode(it).let { message -> message.type to message.fields["reason"] } }} ready=${result.readyToConnect != null}")
        LidAutoconnectDiagnostics.logEvent("passive_receive", mapOf("type" to type,
            "ready" to (result.readyToConnect != null),
            "request" to LidAutoconnectDiagnostics.fingerprint(fields["requestId"]?.toString().orEmpty()),
            "reason" to fields["reason"],
            "participantPhase" to passiveAcquisitionCoordinator?.currentReservation()?.phase,
            "participantRequest" to passiveAcquisitionCoordinator?.currentReservation()?.requestId?.let(LidAutoconnectDiagnostics::fingerprint)))
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
        // Reservations and the final command authorization must never reuse a
        // cached RELEASED snapshot after its freshness window has elapsed.
        val observations = peerOwnershipStore.freshObservations(headsetId) ?: return null
        val result = linkedMapOf<String, PassiveAcquisitionCoordinator.PeerStatus>()
        membersByMac.forEach { (mac, id) ->
            if (!v2AcquisitionCapable.contains(mac.uppercase())) return null
            val observation = observations[mac.uppercase()] ?: run {
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
            LidAutoconnectDiagnostics.logEvent("passive_send", mapOf("type" to message.type,
                "request" to LidAutoconnectDiagnostics.fingerprint(message.fields["requestId"]?.toString().orEmpty()),
                "reason" to message.fields["reason"]))
            val targetIds = when (frame.type) {
                PeerV2Frame.Type.ACQUIRE_REQUEST, PeerV2Frame.Type.ACQUIRE_CANCEL -> byId.keys
                PeerV2Frame.Type.ACQUIRE_START -> setOfNotNull(message.fields["grantorId"] as? String)
                PeerV2Frame.Type.ACQUIRE_GRANT, PeerV2Frame.Type.ACQUIRE_DENY,
                PeerV2Frame.Type.ACQUIRE_START_ACK -> setOfNotNull(message.fields["requesterId"] as? String)
                else -> emptySet()
            }
            targetIds.filter { it != localId }.forEach targetLoop@{ id ->
                val mac = byId[id] ?: return@targetLoop
                sendV2Frame(mac, frame)
            }
        }
    }

    private fun sendInboundV2Frame(mac: String, frame: PeerV2Frame.Frame) {
        val socket = v2ReadySockets[mac.uppercase()] ?: return
        val queue = v2InboundQueues[socket] ?: return
        if (!queue.trySend(frame.encode()).isSuccess) socket.runCatching { close() }
    }

    private fun sendV2ToConfiguredPeers(frame: PeerV2Frame.Frame) {
        configuredPeers.forEach { mac -> sendV2Frame(mac, frame) }
    }

    /** Routes a V2 frame: the phone to phone LE session first, then the inbound socket. */
    private fun sendV2Frame(mac: String, frame: PeerV2Frame.Frame) {
        if (leV2Links.sendFrame(mac, frame)) return
        sendInboundV2Frame(mac, frame)
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

    /** V2 sessions carried over the phone to phone Bluetooth LE links (docs/peer-ble-transport.md). */
    private val leV2Links: PeerLeV2Links by lazy {
        PeerLeV2Links(
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "EveryPodsLeV2").apply { isDaemon = true }
            },
            create = ::newLeV2Session,
            onSessionGone = ::onLeV2SessionGone,
        )
    }

    private fun newLeV2Session(mac: String, central: Boolean, ended: (String) -> Unit): PeerLeV2Session =
        PeerLeV2Session(central, object : PeerLeV2Session.Hooks {
            override fun send(frame: PeerV2Frame.Frame) = PeerLeChannel.send(mac, PeerGattRecords.V2, frame.encode())
            override fun isAuthorized(): Boolean {
                val ctx = gattContext ?: return false
                return isEnabled && configuredPeers.any { it.equals(mac, ignoreCase = true) } &&
                    io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(mac)
            }
            override fun localInstallationId() = installationId
            override fun localSessionId() = processSessionId
            override fun localSnapshot() = this@CrossDevice.localSnapshot()
            override fun newConnectionId() = UUID.randomUUID().toString()
            override fun bindPeerIdentity(installationId: String) { bindV2PeerIdentity(mac, installationId) }
            override fun observeSnapshot(remoteSessionId: String, snapshot: Map<String, Any?>) =
                observeRemoteSnapshot(mac, remoteSessionId, snapshot)
            override fun markReady(remoteSessionId: String, acquisitionCapable: Boolean, intentCapable: Boolean) {
                val first = v2ReadySessions[mac.uppercase()] != remoteSessionId
                markV2PeerReady(mac, remoteSessionId, acquisitionCapable, intentCapable)
                if (first) Log.d(TAG, "[$mac] v2 over Bluetooth LE ready")
                ServiceManager.getService()?.retryRecentRemoteCaseObservation()
            }
            override fun handleAcquisition(remoteSessionId: String, type: PeerV2Frame.Type, fields: Map<String, Any?>) =
                handleV2AcquisitionMessage(mac, remoteSessionId, type, fields)
            override fun onEnded(reason: String) {
                Log.w(TAG, "[$mac] v2 over Bluetooth LE ended ($reason)")
                ended(reason)
            }
        }, { SystemClock.elapsedRealtime() })

    /** An LE V2 session ended or its link went down: forget what it proved, unless RFCOMM V2 still holds the peer. */
    private fun onLeV2SessionGone(mac: String) {
        val key = mac.uppercase()
        if (v2ReadySockets[key]?.let { runCatching { it.isConnected }.getOrDefault(false) } == true) return
        v2ReadySessions.remove(key)
        v2AcquisitionCapable.remove(key)
        peerOwnershipStore.invalidate(key)
    }
    private val v2ReadySockets = ConcurrentHashMap<String, BluetoothSocket>()
    @Volatile private var remoteCaseTransport: io.automated.ventures.everypods.utils.peer.RemoteCaseBluetoothTransport? = null

    private fun remoteCasePeerSession(address: String): UUID? {
        val mac = address.uppercase()
        if (!isEnabled || configuredPeers.none { it.equals(mac, true) } ||
            (v2ReadySockets[mac]?.let { runCatching { it.isConnected }.getOrDefault(false) } != true &&
                !leV2Links.isReady(mac))) return null
        val session = v2ReadySessions[mac] ?: return null
        return runCatching { UUID.fromString(session) }.getOrNull()
    }

    internal fun isRemoteCaseEvidenceCurrent(address: String, session: UUID, observation: UUID, nowMs: Long): Boolean {
        val transport = remoteCaseTransport ?: return false
        return remoteCasePeerSession(address) == session && !transport.isWithdrawn(address, session, observation, nowMs)
    }

    fun publishRemoteCaseObservation(observation: io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceExchange.Observation) {
        remoteCaseTransport?.publish(observation)
    }

    fun publishRemoteCaseWithdrawal(message: io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceMessage.Message.Withdrawal) {
        remoteCaseTransport?.publishWithdrawal(message)
    }

    @Synchronized private fun startRemoteCaseTransport(adapter: android.bluetooth.BluetoothAdapter) {
        if (configuredPeers.isEmpty()) { stopRemoteCaseTransport(); return }
        if (remoteCaseTransport != null) return
        val transport = io.automated.ventures.everypods.utils.peer.RemoteCaseBluetoothTransport(
            object : io.automated.ventures.everypods.utils.peer.RemoteCaseBluetoothTransport.Hooks {
                // Computers don't implement case evidence: dialling them only pages an absent or unwilling PC.
                override fun peers(): Set<String> = if (isEnabled) configuredPeers.filterNot { isComputerPeer(adapter, it) }
                    .mapTo(mutableSetOf()) { it.uppercase() } else emptySet()
                override fun peerSession(address: String): UUID? = remoteCasePeerSession(address)
                override fun currentObservation(id: UUID) = ServiceManager.getService()?.currentRemoteCaseObservation(id)
                override fun receiverState(address: String) = ServiceManager.getService()?.remoteCaseReceiverState(remoteCasePeerSession(address))
                override fun onAccepted(evidence: io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceExchange.Accepted) {
                    ServiceManager.getService()?.acceptRemoteCaseEvidence(evidence)
                }
                override fun onWithdrawal(address: String, message: io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceMessage.Message.Withdrawal) {
                    ServiceManager.getService()?.withdrawRemoteCaseEvidence(address, message)
                }
            }, acquireWake = { timeoutMs ->
                val context = ServiceManager.getService()?.applicationContext
                if (context == null) null else {
                    val manager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val wakeLock = manager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "EveryPods:RemoteCase")
                    wakeLock.acquire(timeoutMs)
                    io.automated.ventures.everypods.utils.peer.RemoteCaseBluetoothTransport.WakeLease {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                }
            })
        remoteCaseTransport = transport
        PeerLeChannel.register(PeerGattRecords.CASE) { mac, payload -> remoteCaseTransport?.onLeRecord(mac, payload) }
    }

    @Synchronized private fun stopRemoteCaseTransport() {
        remoteCaseTransport?.close()
        remoteCaseTransport = null
    }
    private val v2AcquisitionCapable = ConcurrentHashMap.newKeySet<String>()
    private val v2IntentCapable = ConcurrentHashMap.newKeySet<String>()
    internal fun peerSupportsIntent(mac: String): Boolean = v2IntentCapable.contains(mac.uppercase())
    internal fun allSupportIntent(macs: Collection<String>): Boolean = macs.all { peerSupportsIntent(it) }
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
                val activeListener = adapter.listenUsingRfcommWithServiceRecord(endpoint.name, endpoint.uuid)
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
                    val hasCapability = addr != null && connections?.coordinationAuthorized(addr) == true
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
        val v2LinkUpAtMs = SystemClock.elapsedRealtime()
        linkStats.linkUp(address, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.V2)
        val writer = launch(Dispatchers.IO) {
            try {
                for (bytes in outbound) {
                    socket.outputStream.write(bytes)
                    socket.outputStream.flush()
                    linkStats.frameOut(address, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.V2, bytes.size)
                }
            } catch (_: Exception) { socket.runCatching { close() } }
        }
        var helloSeen = false
        var remoteSessionId: String? = null
        var remoteAcquisitionCapable = false
        var remoteIntentCapable = false
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
                    } else {
                        linkStats.ping(address, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.V2)
                        logLinkStatsIfDue()
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
                    linkStats.frameIn(address, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.V2, frame.payload.size)
                    val capabilityContext = ServiceManager.getService()?.applicationContext ?: error("service_unavailable")
                    require(io.automated.ventures.everypods.relay.DeviceConnectionStore(capabilityContext).coordinationAuthorized(address)) {
                        "unauthorized_coordination_peer"
                    }
                    val message = PeerV2Message.decode(frame)
                    if (!helloSeen) {
                        require(message.type == PeerV2Frame.Type.HELLO) { "hello_required" }
                        require(configuredPeers.any { it.equals(address, ignoreCase = true) }) { "unconfigured_peer" }
                        val remote = message.fields
                        val localInstall = installationId ?: error("identity_unavailable")
                        val localSession = processSessionId ?: error("session_unavailable")
                        val remoteInstall = remote.getValue("installationId") as String
                        remoteSessionId = remote.getValue("sessionId") as String
                        val remoteCaps = (remote.getValue("capabilities") as List<*>).filterIsInstance<String>()
                        remoteAcquisitionCapable = "acquire-v1" in remoteCaps
                        remoteIntentCapable = "acquire-v2" in remoteCaps
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
                            "capabilities" to PeerV2Message.LOCAL_CAPABILITIES,
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
                            remoteSessionId?.let { markV2PeerReady(address, it, socket, remoteAcquisitionCapable, remoteIntentCapable) }
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
            linkStats.linkDown(address, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.V2,
                SystemClock.elapsedRealtime() - v2LinkUpAtMs)
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
        val ownership = service?.peerAudioOwnership()?.name?.lowercase() ?: "unknown"
        val audioUse = service?.peerAudioUse() ?: PeerAudioUse.UNKNOWN
        val state = mapOf(
            "headsetId" to targetId,
            "ownership" to ownership,
            "audioUse" to audioUse.name.lowercase(),
            "lastOwnerId" to null,
            "lastOwnerRevision" to null,
            "acquisition" to acquisitionSnapshot(),
        )
        val snapshot = state + mapOf(
            "sessionId" to (processSessionId ?: error("session_unavailable")),
            "revision" to v2SnapshotRevision.forState(state),
        )
        if (ownership == "held" && service?.hasConfirmedPassiveAudioConnection() == true) {
            passiveAcquisitionCoordinator?.currentIntent()?.let { current ->
                if (passiveAcquisitionCoordinator?.requesterResolve(current.requestId, true) == true) {
                    dispatchAcquisitionFrames(passiveAcquisitionProtocol?.replayCancellations().orEmpty())
                }
            }
        }
        return snapshot
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
        LidAutoconnectDiagnostics.logEvent("peer_snapshot", mapOf("accepted" to accepted,
            "peer" to address, "ownership" to ownership, "audioUse" to audioUse,
            "revision" to snapshot["revision"]))
        if (!accepted) return false
        val localHeadset = ServiceManager.getService()?.macAddress?.takeIf { it.isNotBlank() }
            ?.let { PeerIdentityStore.headsetIdFromAddress(it) }
        if (localHeadset != null && snapshot["headsetId"] == localHeadset) {
            if (ownership == PeerOwnership.RELEASED) holders.removeIf { it.equals(address, true) }
            if (ownership == PeerOwnership.HELD) holders.add(address.uppercase())
        }
        if (ownership == PeerOwnership.HELD && localHeadset != null && snapshot["headsetId"] == localHeadset) {
            passiveAcquisitionCoordinator?.observePeerHeld(expectedInstallId, localHeadset)
        }
        passiveAcquisitionCoordinator?.observePeerAcquisition(expectedInstallId,
            (snapshot["acquisition"] as? Map<*, *>)?.get("requestId") as? String)
        // The v2 HELD field also includes AACP-only ownership. It cannot prove
        // that an acknowledged A2DP command has completed; the requester sends
        // an authenticated terminal release after observing the profile result.
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
            passiveAcquisitionCoordinator = PassiveAcquisitionCoordinator(identity.installationId, clock).apply {
                transitionListener = { event ->
                    LidAutoconnectDiagnostics.logEvent("reservation_transition", mapOf(
                        "role" to event.role, "request" to LidAutoconnectDiagnostics.fingerprint(event.requestId),
                        "phase" to event.phase, "reason" to event.reason, "commandIssued" to event.commandIssued,
                    ))
                }
            }
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
            PeerGattServer.stop("coordination disabled")
            PeerGattClient.stop("coordination disabled")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled, skipping server start")
            PeerGattServer.stop("bluetooth off")
            PeerGattClient.stop("bluetooth off")
            return
        }
        startServer(adapter)
        gattContext = context.applicationContext
        PeerLeChannel.authorizer = ::isLeAuthorizedPeer
        PeerLeChannel.register(PeerGattRecords.V2) { mac, payload -> leV2Links.onRecord(mac, payload) }
        if (configuredPeers.isEmpty()) {
            PeerGattServer.stop("no peers")
            PeerGattClient.stop("no peers")
        } else {
            PeerGattServer.start(context, gattListener)
            PeerGattClient.start(context, gattClientListener)
        }
        refreshPeerLinks()
    }

    /**
     * Reconciles the outbound links. Phones are dialled over Bluetooth LE by the elected central
     * ([PeerLinkElection]). The reconcile keeps links that are still wanted, so calling this on every link
     * change does not tear anything down needlessly.
     */
    @SuppressLint("MissingPermission")
    internal fun refreshPeerLinks() {
        val ctx = gattContext ?: return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!isEnabled || !adapter.isEnabled) return
        val connections = io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx)
        val authorized = configuredPeers.map { it.uppercase() }.filter { connections.coordinationAuthorized(it) }
        val ownId = PeerIdentityStore.load(ctx).installationId
        val gattTargets = authorized.filter {
            !isComputerPeer(adapter, it) && PeerLinkElection.shouldDial(ownId, knownPeerInstallationId(it))
        }.toSet() + pendingSetupPhones(adapter, connections)
        PeerGattClient.setTargets(gattTargets)
        PeerGattServer.refreshAdvertisingRate()
    }

    /** Phones with a setup change to deliver: dialled over Bluetooth LE whatever the election says (setup link). */
    private fun pendingSetupPhones(
        adapter: android.bluetooth.BluetoothAdapter,
        connections: io.automated.ventures.everypods.relay.DeviceConnectionStore,
    ): Set<String> = connections.pendingHandoverChanges().keys.map { it.uppercase() }
        .filter { !isComputerPeer(adapter, it) }.toSet()

    /** A configured, coordination-authorized phone (not a computer) may use the LE channel. */
    private fun isLeAuthorizedPeer(mac: String): Boolean {
        val ctx = gattContext ?: return false
        if (!isEnabled || configuredPeers.none { it.equals(mac, ignoreCase = true) }) return false
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return !isComputerPeer(adapter, mac) &&
            io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(mac)
    }

    private fun isLinkedOverGatt(mac: String): Boolean = PeerGattServer.isReady(mac) || isAuthorizedGattClientLink(mac)

    @SuppressLint("MissingPermission")
    private fun isComputerPeer(adapter: android.bluetooth.BluetoothAdapter, mac: String): Boolean = runCatching {
        adapter.getRemoteDevice(mac).bluetoothClass?.majorDeviceClass ==
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER
    }.getOrDefault(false)

    // Installation IDs learned from peers' HELLOs, by Bluetooth address (drives [PeerLinkElection]).
    private const val PEER_IDS_PREFS = "peer_gatt_installation_ids"

    private fun knownPeerInstallationId(mac: String): String? =
        gattContext?.getSharedPreferences(PEER_IDS_PREFS, Context.MODE_PRIVATE)?.getString(mac.uppercase(), null)

    private fun rememberPeerInstallationId(mac: String, installationId: String) {
        val prefs = gattContext?.getSharedPreferences(PEER_IDS_PREFS, Context.MODE_PRIVATE) ?: return
        if (prefs.getString(mac.uppercase(), null) != installationId) {
            prefs.edit().putString(mac.uppercase(), installationId).apply()
        }
    }

    private fun forgetPeerInstallationId(mac: String) {
        gattContext?.getSharedPreferences(PEER_IDS_PREFS, Context.MODE_PRIVATE)?.edit()?.remove(mac.uppercase())?.apply()
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
        startRemoteCaseTransport(adapter)
    }

    private fun invalidateServerGeneration() {
        stopRemoteCaseTransport()
        stopEndpoint(legacyEndpoint)
        stopEndpoint(v2Endpoint)
    }

    @Volatile private var gattContext: Context? = null

    /** Bluetooth LE peer links (computers dial this phone over GATT; docs/peer-ble-transport.md). */
    private val gattListener = object : PeerGattServer.Listener {
        override fun isAllowedPeer(address: String): Boolean {
            val ctx = gattContext ?: return false
            return isEnabled && configuredPeers.any { it.equals(address, ignoreCase = true) } &&
                io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(address)
        }

        @SuppressLint("MissingPermission")
        override fun localHello(): PeerGattRecords.Hello {
            val ctx = gattContext
            val name = runCatching { ctx?.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull() ?: ""
            val id = ctx?.let { PeerIdentityStore.load(it).installationId } ?: java.util.UUID.randomUUID().toString()
            return PeerGattRecords.Hello(id, name, listOf("legacy1"))
        }

        override fun onSessionReady(address: String, hello: PeerGattRecords.Hello) {
            Log.d(TAG, "[$address] Bluetooth LE link ready ('${hello.name}')")
            rememberPeerInstallationId(address, hello.installationId)
            refreshPeerLinks()
            gattContext?.let {
                it.sendBroadcast(Intent("io.automated.ventures.everypods.AIRPODS_CONNECTED_REMOTELY").setPackage(it.packageName))
            }
            // Tell only this peer our current AirPods state, as the RFCOMM server does for a new client.
            PeerGattServer.send(address,
                if (ServiceManager.getService()?.holdsAirPods() == true) CrossDevicePackets.AIRPODS_CONNECTED.packet
                else CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
            startLeV2(address, hello, central = false)
        }

        override fun onLegacyPacket(address: String, packet: ByteArray) = processPacket(packet, address)

        override fun onRecord(address: String, type: Int, payload: ByteArray) = PeerLeChannel.onRecord(address, type, payload)

        override fun onSessionEnded(address: String) {
            Log.d(TAG, "[$address] Bluetooth LE link ended")
            endLeV2(address, central = false)
            onPeerTransportDisconnected(address)
            refreshPeerLinks()
        }

        override fun onKeepAliveDue(address: String) {
            PeerGattServer.send(address, CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
        }

        /** Fast while a computer that links over LE (it has sent a HELLO before) is missing; Windows never does. */
        override fun wantsFastAdvertising(readyAddresses: Set<String>): Boolean {
            val ctx = gattContext ?: return readyAddresses.isEmpty()
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return readyAddresses.isEmpty()
            val computers = configuredPeers.map { it.uppercase() }
                .filter { isComputerPeer(adapter, it) && knownPeerInstallationId(it) != null }
            if (computers.isEmpty()) return readyAddresses.isEmpty()
            return computers.any { it !in readyAddresses }
        }
    }

    /** Phone-to-phone links where this phone is the Bluetooth LE central ([PeerGattClient]). */
    private val gattClientListener = object : PeerGattClient.Listener {
        override fun localHello(): PeerGattRecords.Hello = gattListener.localHello()

        override fun onSessionReady(address: String, hello: PeerGattRecords.Hello) {
            rememberPeerInstallationId(address, hello.installationId)
            val ctx = gattContext
            if (ctx != null && !io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(address)) {
                // Not a peer yet: this link carries the setup exchange only (BtHandoverSync sends SETUP_REQ).
                Log.d(TAG, "[$address] Bluetooth LE link to '${hello.name}' ready for setup")
                io.automated.ventures.everypods.relay.BtHandoverSync.wake(address)
                return
            }
            val ownId = gattContext?.let { PeerIdentityStore.load(it).installationId }
            if (ownId == null || !PeerLinkElection.keepCentralLink(ownId, hello.installationId)) {
                // First contact: both phones dialled. The other phone is the elected central and keeps its link.
                Log.d(TAG, "[$address] '${hello.name}' is the Bluetooth LE central for this pair; closing ours")
                PeerGattClient.drop(address, "peer is the central", GATT_CENTRAL_HOLD_OFF_MS)
                refreshPeerLinks()
                return
            }
            Log.d(TAG, "[$address] Bluetooth LE link ready as central ('${hello.name}')")
            refreshPeerLinks()
            gattContext?.let {
                it.sendBroadcast(Intent("io.automated.ventures.everypods.AIRPODS_CONNECTED_REMOTELY").setPackage(it.packageName))
            }
            PeerGattClient.send(address,
                if (ServiceManager.getService()?.holdsAirPods() == true) CrossDevicePackets.AIRPODS_CONNECTED.packet
                else CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
            startLeV2(address, hello, central = true)
        }

        override fun onLegacyPacket(address: String, packet: ByteArray) = processPacket(packet, address)

        override fun onSessionEnded(address: String) {
            Log.d(TAG, "[$address] Bluetooth LE central link ended")
            endLeV2(address, central = true)
            onPeerTransportDisconnected(address)
            refreshPeerLinks()
        }

        override fun onKeepAliveDue(address: String) {
            PeerGattClient.send(address, CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
        }

        override fun onRecord(address: String, type: Int, payload: ByteArray) {
            if (type == PeerGattRecords.SETUP_ACK) io.automated.ventures.everypods.relay.BtHandoverSync.onSetupAck(address, payload)
            else PeerLeChannel.onRecord(address, type, payload)
        }
    }

    /**
     * Starts V2, case evidence and relay signalling over a kept, authorized phone to phone LE link. The
     * peripheral side only starts for the elected central's link (the other phone closes its own otherwise).
     */
    private fun startLeV2(address: String, hello: PeerGattRecords.Hello, central: Boolean) {
        val ctx = gattContext ?: return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val computer = isComputerPeer(adapter, address)
        // V2 runs with computers too (they vote in passive acquisition); relay and case evidence stay phone-only
        // through PeerLeChannel's authorizer. A computer is always the central, so no election applies to it.
        if (computer) {
            if (!isEnabled || configuredPeers.none { it.equals(address, ignoreCase = true) } ||
                !io.automated.ventures.everypods.relay.DeviceConnectionStore(ctx).coordinationAuthorized(address)) return
        } else {
            if (!isLeAuthorizedPeer(address)) return
            if (!central) {
                val ownId = PeerIdentityStore.load(ctx).installationId
                if (!PeerLinkElection.keepCentralLink(hello.installationId, ownId)) return
            }
        }
        PeerLeChannel.onLinkUp(address, central)
        leV2Links.linkUp(address, central)
    }

    private fun endLeV2(address: String, central: Boolean) {
        if (leV2Links.linkDown(address, central)) PeerLeChannel.onLinkDown(address)
    }

    /** How long a phone that lost the central election waits before it may dial that peer again. */
    private const val GATT_CENTRAL_HOLD_OFF_MS = 60_000L

    @SuppressLint("MissingPermission")
    fun onBluetoothStateChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            init(context.applicationContext)
            return
        }
        linkStats.take(SystemClock.elapsedRealtime()).forEach { Log.i(TAG, it) }  // the partial window
        invalidateServerGeneration()
        closeV2ClientSockets()
        val oldPeers = clientSockets.mapNotNull { socket ->
            val addr = runCatching { socket.remoteDevice.address }.getOrNull()
            socket.runCatching { close() }
            addr
        }
        clientSockets.clear()
        oldPeers.forEach(::onPeerTransportDisconnected)
        PeerGattServer.stop("bluetooth off")
        PeerGattClient.stop("bluetooth off")
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
        // peer every 20 s keeps it alive. The peer already
        // replies to REQUEST_CONNECTION_STATUS, so no protocol change is needed.
        val linkUpAtMs = SystemClock.elapsedRealtime()
        val liveness = io.automated.ventures.everypods.utils.peer.LegacyPeerLiveness(linkUpAtMs)
        val decoder = io.automated.ventures.everypods.utils.peer.LegacyPeerStreamDecoder()
        linkStats.linkUp(addr, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.LEGACY)
        LidAutoconnectDiagnostics.logConnectionEvent("legacy_server", "opened")
        val keepAliveJob: Job = clientScope.launch {
            while (true) {
                delay(SERVER_KEEPALIVE_INTERVAL_MS)
                if (!clientSockets.contains(socket)) break
                // Check before asking again, never right after: the reply to this
                // request cannot have arrived yet (see LegacyPeerLiveness).
                if (liveness.staleBeforeRequest(SystemClock.elapsedRealtime())) {
                    LidAutoconnectDiagnostics.logConnectionEvent("legacy_server", "timeout", mapOf(
                        "ageMs" to liveness.responseAgeMs(SystemClock.elapsedRealtime())))
                    Log.w(TAG, "Server heartbeat status unanswered → $addr (closing stale link)")
                    socket.runCatching { close() }
                    break
                }
                val ok = sendToSocket(socket, CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)

                if (!ok) {
                    // W5: a failed write means the link is dying. A GAP in these
                    // timestamps with no failure line means the process was frozen.
                    Log.w(TAG, "Server keep-alive ping FAILED → $addr (link dying)")
                    break
                }
                liveness.onRequestSent(SystemClock.elapsedRealtime())
                linkStats.ping(addr, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.LEGACY)
                logLinkStatsIfDue()
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
            for (packet in decoder.feed(buffer.copyOf(bytes))) {
                linkStats.frameIn(addr, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.LEGACY, packet.size)
                if (packet.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) ||
                    packet.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)) {
                    liveness.onStatusResponse(SystemClock.elapsedRealtime())
                }
                processPacket(packet, addr)
            }
        }

        keepAliveJob.cancel()
        linkStats.linkDown(addr, io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.LEGACY,
            SystemClock.elapsedRealtime() - linkUpAtMs)
        LidAutoconnectDiagnostics.logConnectionEvent("legacy_server", "closed", mapOf(
            "ageMs" to liveness.responseAgeMs(SystemClock.elapsedRealtime())))
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
     * ([PeerLeV2Session]) both route here, so the protocol lives in one place.
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

    /**
     * Legacy REQUEST_HANDOVER / REQUEST_DISCONNECT. Only mark the peer as holder when we
     * *accept* (pod in ear / unknown); ignoring when pods are in the case must NOT sticky-add
     * the peer — that blocked lid-open autoconnect via peer_holding. Then the holder-side grant
     * policy decides (spec §4.3): grant yields, deny keeps the AirPods and tells the requester.
     */
    private fun handleLegacyHandoverRequest(kind: LegacyHandoverRequest.Kind, sourceMac: String) {
        val service = ServiceManager.getService()
        if (!shouldAcceptHandoverRequest()) {
            holders.remove(sourceMac)
            Log.d(TAG, "[$sourceMac] $kind ignored — no pod in ear (pods in case)")
            return
        }
        val action = LegacyHandoverRequest.resolve(kind) { intent ->
            service?.peerHandoverDecision(intent) ?: GrantDecision.Grant
        }
        // A call refusal takes the pre-existing path unchanged: disconnectForCD() applies the
        // call-hold veto itself (it broadcasts AIRPODS_CONNECTED and keeps the AirPods).
        val callVeto = action is LegacyHandoverRequest.Action.ReplyStillHeld &&
            action.reason == GrantDenial.CALL_ACTIVE
        when {
            action == LegacyHandoverRequest.Action.Yield || callVeto -> {
                Log.d(TAG, "[$sourceMac] $kind — releasing AirPods (eagerly marking peer as holder)")
                holders.add(sourceMac)
                service?.markPeerTakeoverAttempt()
                service?.disconnectForCD()
            }
            action is LegacyHandoverRequest.Action.ReplyStillHeld -> {
                Log.d(TAG, "[$sourceMac] $kind denied (${action.reason}) — keeping AirPods, broadcasting AIRPODS_CONNECTED")
                notifyConnected()   // broadcast, like the former call-hold veto
            }
        }
    }

    internal fun processPacket(raw: ByteArray, sourceMac: String, context: Context? = ServiceManager.getService()?.applicationContext) {
        if (context == null || !io.automated.ventures.everypods.relay.DeviceConnectionStore(context).coordinationAuthorized(sourceMac)) {
            Log.w(TAG, "Ignoring packet from peer without an authorized coordination capability")
            return
        }
        if (raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) || raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet)) {
            val allowsHandover = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("cross_device_enabled", true) &&
                io.automated.ventures.everypods.relay.DeviceConnectionStore(context).handoverReady(sourceMac)
            if (!allowsHandover) {
                Log.i(TAG, "[$sourceMac] Ignoring handover request: no enabled BT handover connection")
                return
            }
        }
        Log.d(TAG, "[$sourceMac] received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) ->
                handleLegacyHandoverRequest(LegacyHandoverRequest.Kind.HANDOVER, sourceMac)
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) ->
                handleLegacyHandoverRequest(LegacyHandoverRequest.Kind.DISCONNECT, sourceMac)
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                holders.add(sourceMac)
                ownershipObservations[sourceMac.uppercase()] =
                    OwnershipObservation(true, SystemClock.elapsedRealtime())
                // Peer explicitly announced ownership — if we were expecting a takeover,
                // arm the peer-drop cooldown proactively (don't wait for ACL_DISCONNECTED).
                ServiceManager.getService()?.confirmPeerOwnership()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) -> {
                holders.remove(sourceMac)
                ownershipObservations[sourceMac.uppercase()] =
                    OwnershipObservation(false, SystemClock.elapsedRealtime())
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
            io.automated.ventures.everypods.utils.peer.PeerBatteryPacket.isPacket(raw) -> {
                // Advert-derived battery from a peer: never implies the peer holds the AirPods.
                io.automated.ventures.everypods.utils.peer.PeerBatteryPacket.decode(raw)?.let {
                    ServiceManager.getService()?.onPeerBattery(sourceMac, it)
                }
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

    /** Send to every configured peer that is currently linked, over whichever transport reaches it. */
    fun sendToConnectedPeers(data: ByteArray) {
        configuredPeers.filter(::isConnectedTo).forEach { sendTo(it, data) }
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
        sendOverGatt(data)
    }

    /** Sends to each peer linked over Bluetooth LE that has no RFCOMM socket to us (one link per pair). */
    private fun sendOverGatt(data: ByteArray) {
        val viaSocket = clientSockets.mapNotNullTo(mutableSetOf()) {
            runCatching { it.remoteDevice.address.uppercase() }.getOrNull()
        }
        configuredPeers.map { it.uppercase() }
            .filter { it !in viaSocket && PeerGattServer.isReady(it) }
            .forEach { PeerGattServer.send(it, data) }
    }

    // Never hold the BluetoothSocket monitor across a blocking write: close()
    // needs that monitor to interrupt dead I/O. Weak keys avoid retaining old links.
    private val legacyWriteLocks = java.util.WeakHashMap<BluetoothSocket, Any>()

    private fun sendToSocket(socket: BluetoothSocket, data: ByteArray, onFail: (() -> Unit)? = null): Boolean {
        val hex = data.joinToString("") { "%02x".format(it) }
        try {
            val writeLock = synchronized(legacyWriteLocks) { legacyWriteLocks.getOrPut(socket) { Any() } }
            synchronized(writeLock) {
                socket.outputStream.write(data)
                socket.outputStream.flush()
            }
            linkStats.frameOut(socket.remoteDevice.address,
                io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel.LEGACY, data.size)
            Log.d(TAG, "Sent to ${socket.remoteDevice.address}: $hex")
            return true
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send to ${socket.remoteDevice.address}: ${e.message}")
            onFail?.invoke()
            return false
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
        if (PeerGattServer.send(mac, data)) return
        if (PeerGattClient.send(mac, data)) return
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
        sendOverGattClient(data)
    }

    /** Sends to each phone this phone is the LE central for, unless another link already carried the packet. */
    private fun sendOverGattClient(data: ByteArray) {
        val viaSocket = clientSockets.mapNotNullTo(mutableSetOf()) {
            runCatching { it.remoteDevice.address.uppercase() }.getOrNull()
        }
        configuredPeers.map { it.uppercase() }
            .filter {
                it !in viaSocket && !PeerGattServer.isReady(it) && isAuthorizedGattClientLink(it)
            }
            .forEach { PeerGattClient.send(it, data) }
    }

    fun notifyConnected() {
        broadcast(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    }
    fun notifyDisconnected() {
        broadcast(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
    }

    fun close() {
        PeerGattServer.stop("coordination off")
        PeerGattClient.stop("coordination off")
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
        stopRemoteCaseTransport()
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
