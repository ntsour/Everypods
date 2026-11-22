package io.automated.ventures.everypods.utils.peer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Optional, separate secure RFCOMM endpoint. Old peers never receive extension
 * frames on their v2 socket. All peer/session and policy checks are delegated
 * to hooks which must query current state, not a saved permission to connect.
 */
@SuppressLint("MissingPermission")
class RemoteCaseBluetoothTransport(
    private val adapter: BluetoothAdapter,
    private val hooks: Hooks,
    private val acquireWake: (Long) -> WakeLease? = { null },
    private val timeoutMs: Long = EXCHANGE_TIMEOUT_MS,
    private val le: LeLink = PeerLeCaseLink,
) {
    /** The phone to phone Bluetooth LE route; RFCOMM stays the fallback when it is not ready. */
    interface LeLink {
        fun isReady(address: String): Boolean
        fun send(address: String, payload: ByteArray): Boolean
    }

    fun interface WakeLease { fun release() }
    init { require(timeoutMs in 1..EXCHANGE_TIMEOUT_MS) }
    interface Hooks {
        fun peers(): Set<String>
        fun peerSession(address: String): UUID?
        fun currentObservation(id: UUID): RemoteCaseEvidenceExchange.Observation?
        fun receiverState(address: String): RemoteCaseEvidenceExchange.ReceiverState?
        fun onAccepted(evidence: RemoteCaseEvidenceExchange.Accepted)
        fun onWithdrawal(address: String, message: RemoteCaseEvidenceMessage.Message.Withdrawal)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "EveryPodsCaseDeadline").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<BluetoothSocket>()
    private val leases = mutableMapOf<BluetoothSocket, WakeLease>()
    private val sending = ConcurrentHashMap.newKeySet<String>()
    private val gate = RemoteCaseEvidenceGate()
    private data class LeKey(val address: String, val localRole: Int, val id: Int)
    private val leExchanges = ConcurrentHashMap<LeKey, LeExchangeStream>()
    private val leCounters = ConcurrentHashMap<String, Int>()
    @Volatile private var listener: BluetoothServerSocket? = null
    @Volatile private var closed = false

    @Synchronized fun start() {
        if (closed || listener != null) return
        val server = try { adapter.listenUsingRfcommWithServiceRecord("EveryPodsCaseEvidence", SERVICE_UUID) }
        catch (e: Exception) { Log.w(TAG, "Case evidence endpoint unavailable: ${e.javaClass.simpleName}"); return }
        listener = server
        scope.launch {
            try {
                while (isActive && !closed && listener === server) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    if (sockets.size >= MAX_OPEN_EXCHANGES) { runCatching { socket.close() }; continue }
                    sockets.add(socket)
                    if (closed) { closeSocket(socket); break }
                    scope.launch { receive(socket) }
                }
            } finally {
                runCatching { server.close() }
                synchronized(this@RemoteCaseBluetoothTransport) { if (listener === server) listener = null }
            }
        }
    }

    /** At most one bounded outgoing exchange per configured, synchronized peer. */
    fun publish(observation: RemoteCaseEvidenceExchange.Observation) {
        if (closed || !observation.identityVerified || !observation.available) return
        hooks.peers().forEach { address ->
            val normalized = address.uppercase()
            val expectedSession = hooks.peerSession(normalized) ?: return@forEach
            if (!sending.add(normalized)) return@forEach
            scope.launch {
                try {
                    if (closed || hooks.peerSession(normalized) != expectedSession) return@launch
                    if (le.isReady(normalized)) {
                        val stream = openLeExchange(normalized) ?: return@launch
                        withLeDeadline(stream) {
                            val sent = RemoteCaseEvidenceExchange.send(stream.input, stream.output, observation,
                                expectedSession, { hooks.peerSession(normalized) }, { hooks.currentObservation(observation.id) })
                            io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("remote_case_observer", mapOf("confirmed" to sent, "link" to "le"))
                        }
                        return@launch
                    }
                    val device = adapter.getRemoteDevice(normalized)
                    if (device.bondState != BluetoothDevice.BOND_BONDED) return@launch
                    val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    sockets.add(socket)
                    if (closed) { closeSocket(socket); return@launch }
                    withDeadline(socket) {
                        socket.connect()
                        val sent = RemoteCaseEvidenceExchange.send(socket.inputStream, socket.outputStream, observation,
                            expectedSession, { hooks.peerSession(normalized) }, { hooks.currentObservation(observation.id) })
                        io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("remote_case_observer", mapOf("confirmed" to sent))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Case evidence peer unavailable: ${e.javaClass.simpleName}")
                    io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("remote_case_observer", mapOf("result" to "unavailable"))
                } finally { sending.remove(normalized) }
            }
        }
    }

    /** A keyed stored observation revokes the specific earlier event on each ready peer. */
    fun publishWithdrawal(message: RemoteCaseEvidenceMessage.Message.Withdrawal) {
        if (closed) return
        hooks.peers().forEach { address ->
            val normalized = address.uppercase()
            val expected = hooks.peerSession(normalized) ?: return@forEach
            val key = "$normalized/withdraw/${message.observation}"
            if (sending.size >= MAX_OPEN_EXCHANGES || !sending.add(key)) return@forEach
            scope.launch {
                try {
                    if (!closed && hooks.peerSession(normalized) == expected && le.isReady(normalized)) {
                        val stream = openLeExchange(normalized) ?: return@launch
                        withLeDeadline(stream) {
                            if (hooks.peerSession(normalized) == expected) {
                                stream.output.write(RemoteCaseEvidenceMessage.encode(message))
                                stream.output.flush()
                            }
                        }
                        return@launch
                    }
                    val device = adapter.getRemoteDevice(normalized)
                    if (closed || device.bondState != BluetoothDevice.BOND_BONDED || hooks.peerSession(normalized) != expected) return@launch
                    val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    sockets.add(socket)
                    if (closed) { closeSocket(socket); return@launch }
                    withDeadline(socket) {
                        socket.connect()
                        if (hooks.peerSession(normalized) == expected) {
                            socket.outputStream.write(RemoteCaseEvidenceMessage.encode(message))
                            socket.outputStream.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Case evidence withdrawal unavailable: ${e.javaClass.simpleName}")
                } finally { sending.remove(key) }
            }
        }
    }

    private fun receive(socket: BluetoothSocket) {
        try {
            val device = socket.remoteDevice
            val address = device.address.uppercase()
            if (device.bondState != BluetoothDevice.BOND_BONDED || address !in hooks.peers() ||
                hooks.peerSession(address) == null) return
            withDeadline(socket) { handleIncoming(address, socket.inputStream, socket.outputStream) }
        } catch (e: Exception) {
            Log.d(TAG, "Case evidence exchange ended: ${e.javaClass.simpleName}")
        } finally { closeSocket(socket) }
    }

    /** The receiver half shared by RFCOMM sockets and LE exchanges; the caller enforces the deadline. */
    private fun handleIncoming(address: String, input: java.io.InputStream, output: java.io.OutputStream) {
        val first = RemoteCaseEvidenceMessage.read(input) ?: return
        if (first is RemoteCaseEvidenceMessage.Message.Withdrawal) {
            val state = hooks.receiverState(address) ?: return
            if (hooks.peerSession(address) != first.session || state.remoteSession != first.session || state.headset != first.headset) return
            gate.withdraw(address, first.session.toString(), first.observation.toString(), android.os.SystemClock.elapsedRealtime())
            hooks.onWithdrawal(address, first)
            io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("remote_case_withdrawn")
            return
        }
        val accepted = RemoteCaseEvidenceExchange.receive(input, output, address,
            { hooks.receiverState(address) ?: throw IllegalStateException("peer_unavailable") },
            { android.os.SystemClock.elapsedRealtime() }, UUID::randomUUID, gate, first)
        io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("remote_case_receiver", mapOf("accepted" to (accepted != null)))
        if (accepted != null && !closed) hooks.onAccepted(accepted)
    }

    /**
     * A CASE record from the LE link ([PeerLeChannel] handler): `role ‖ exchangeId (u16) ‖ EPC1 bytes`. Records of a
     * known exchange feed its stream; a new exchange (role 0) starts a receiver on [scope].
     */
    fun onLeRecord(address: String, payload: ByteArray) {
        if (closed || payload.size <= LE_HEADER) return
        val mac = address.uppercase()
        val role = payload[0].toInt() and 0xFF
        val id = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        if (role > 1) return
        val bytes = payload.copyOfRange(LE_HEADER, payload.size)
        // A record from the opener (role 0) belongs to our responder stream, and the reverse.
        val key = LeKey(mac, if (role == 0) 1 else 0, id)
        leExchanges[key]?.let { it.deliver(bytes); return }
        if (role != 0 || leExchanges.size >= MAX_OPEN_EXCHANGES) return
        if (mac !in hooks.peers() || hooks.peerSession(mac) == null || !le.isReady(mac)) return
        val stream = newLeStream(mac, 1, id)
        if (leExchanges.putIfAbsent(key, stream) != null) return
        stream.deliver(bytes)
        scope.launch {
            try {
                withLeDeadline(stream) { handleIncoming(mac, stream.input, stream.output) }
            } catch (e: Exception) {
                Log.d(TAG, "Case evidence exchange ended: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun newLeStream(mac: String, role: Int, id: Int) = LeExchangeStream(mac, role, id) { r, i, bytes ->
        val out = ByteArray(LE_HEADER + bytes.size)
        out[0] = r.toByte(); out[1] = (i ushr 8).toByte(); out[2] = i.toByte()
        bytes.copyInto(out, LE_HEADER)
        le.send(mac, out)
    }

    /** Opens an exchange we start (role 0) with a fresh per-peer id; null if too many are open. */
    private fun openLeExchange(mac: String): LeExchangeStream? {
        if (leExchanges.size >= MAX_OPEN_EXCHANGES) return null
        val id = leCounters.compute(mac) { _, v -> ((v ?: 0) + 1) and 0xFFFF }!!
        val stream = newLeStream(mac, 0, id)
        if (leExchanges.putIfAbsent(LeKey(mac, 0, id), stream) != null) return null
        return stream
    }

    /** Closing the stream unblocks a pending read, as closing the socket does for RFCOMM. */
    private inline fun <T> withLeDeadline(stream: LeExchangeStream, block: () -> T): T {
        val lease = runCatching { acquireWake(timeoutMs + 1_000L) }.getOrNull()
        var timeout: ScheduledFuture<*>? = null
        try {
            timeout = timer.schedule({ stream.close() }, timeoutMs, TimeUnit.MILLISECONDS)
            return block()
        } finally {
            timeout?.cancel(false)
            stream.close()
            leExchanges.remove(LeKey(stream.mac, stream.role, stream.id), stream)
            lease?.let { runCatching { it.release() } }
        }
    }

    fun isWithdrawn(peerId: String, session: UUID, observation: UUID, nowMs: Long): Boolean =
        gate.isWithdrawn(peerId.uppercase(), session.toString(), observation.toString(), nowMs)

    /** Closing the socket also unblocks Bluetooth's blocking connect/read calls. */
    private inline fun <T> withDeadline(socket: BluetoothSocket, block: () -> T): T {
        val lease = runCatching { acquireWake(timeoutMs + 1_000L) }.getOrNull()
        if (lease != null && !attachLease(socket, lease)) throw java.io.IOException("transport_closed")
        var timeout: ScheduledFuture<*>? = null
        try {
            timeout = timer.schedule({ closeSocket(socket) }, timeoutMs, TimeUnit.MILLISECONDS)
            return block()
        } finally { timeout?.cancel(false); closeSocket(socket) }
    }

    private fun closeSocket(socket: BluetoothSocket) {
        val lease = synchronized(this) { sockets.remove(socket); leases.remove(socket) }
        runCatching { socket.close() }
        lease?.let { runCatching { it.release() } }
    }

    private fun attachLease(socket: BluetoothSocket, lease: WakeLease): Boolean = synchronized(this) {
        if (closed || socket !in sockets) { runCatching { lease.release() }; false }
        else { leases[socket] = lease; true }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        listener?.runCatching { close() }
        listener = null
        sockets.toList().forEach(::closeSocket)
        leExchanges.values.toList().forEach { it.close() }
        leExchanges.clear()
        gate.cancel()
        scope.cancel()
        timer.shutdownNow()
    }

    companion object {
        private const val TAG = "RemoteCaseEvidence"
        private const val EXCHANGE_TIMEOUT_MS = 3_000L
        private const val MAX_OPEN_EXCHANGES = 16
        private const val LE_HEADER = 3
        val SERVICE_UUID: UUID = UUID.fromString("f8ad4759-e00b-4f49-8e46-52351feb36e3")
    }
}

/** The default [RemoteCaseBluetoothTransport.LeLink]: CASE records over [PeerLeChannel]. */
internal object PeerLeCaseLink : RemoteCaseBluetoothTransport.LeLink {
    override fun isReady(address: String) = PeerLeChannel.isReady(address)
    override fun send(address: String, payload: ByteArray) = PeerLeChannel.send(address, PeerGattRecords.CASE, payload)
}
