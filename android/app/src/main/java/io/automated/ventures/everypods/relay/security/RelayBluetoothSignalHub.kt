/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.automated.ventures.everypods.utils.CrossDevice
import io.automated.ventures.everypods.utils.peer.PeerGattRecords
import io.automated.ventures.everypods.utils.peer.PeerLeChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Length-framed signaling link over the already-bonded Bluetooth ACL. Between two phones with a ready
 * Bluetooth LE link ([PeerLeChannel]) it runs over RELAY records (OPEN / OPEN_ACK / DATA / CLOSE); RFCOMM
 * is the fallback when no LE link is ready.
 */
class RelayBluetoothSignalHub(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onPeerConnected(mac: String, initiator: Boolean)
        fun onMessage(mac: String, payload: ByteArray)
        fun onPeerDisconnected(mac: String)
        fun onFailure(mac: String?, reason: String)
    }

    private val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        ?: error("Bluetooth is unavailable")
    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var serverStarting = false
    /** An OPEN this phone sent and the peer has not answered yet. [id] names the link it will create. */
    private class PendingOpen(val id: Int, val timeout: ScheduledFuture<*>)
    private val pendingOpens = ConcurrentHashMap<String, PendingOpen>()
    private val random = java.security.SecureRandom()
    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "EveryPodsRelayLeOpen").apply { isDaemon = true } }
    }
    private val leLinkListener = object : PeerLeChannel.LinkListener {
        override fun onLinkUp(mac: String, central: Boolean) = Unit
        override fun onLinkDown(mac: String) {
            pendingOpens[mac]?.let { failPendingOpen(mac, it.id, "Bluetooth LE link closed") }
            (links[mac] as? LeLink)?.close()
        }
    }

    init {
        PeerLeChannel.register(PeerGattRecords.RELAY) { mac, payload -> onRelay(mac, payload) }
        PeerLeChannel.addLinkListener(leLinkListener)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startListening() {
        check(!closed.get())
        if (server != null || serverStarting) return
        serverStarting = true
        Thread({
            try {
                val socketServer = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = socketServer
                serverStarting = false
                while (!closed.get()) {
                    val socket = socketServer.accept() ?: break
                    val mac = socket.remoteDevice.address.uppercase()
                    if (socket.remoteDevice.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                        socket.close()
                        continue
                    }
                    attach(socket, initiator = false)
                }
            } catch (error: Exception) {
                if (!closed.get()) listener.onFailure(null, error.message ?: error.javaClass.simpleName)
            } finally {
                server = null
                serverStarting = false
            }
        }, "EveryPodsRelaySignalServer").start()
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        check(!closed.get())
        val mac = macAddress.uppercase()
        require(CrossDevice.configuredPeers.any { it.equals(mac, ignoreCase = true) }) {
            "Connect only to a configured EveryPods peer"
        }
        if (links.containsKey(mac) || !connecting.add(mac)) return
        if (PeerLeChannel.isReady(mac)) {
            val id = random.nextInt()
            pendingOpens.remove(mac)?.timeout?.cancel(false)
            pendingOpens[mac] = PendingOpen(id, timer.schedule({ failPendingOpen(mac, id, "Bluetooth LE peer did not answer") },
                OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            Log.i(TAG, "[$mac] relay signaling OPEN over Bluetooth LE (link ${id.toUInt().toString(16)})")
            if (sendRelay(mac, KIND_OPEN, id)) return
            pendingOpens.remove(mac)?.timeout?.cancel(false)
        }
        Thread({
            var socket: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                socket = adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                attach(socket, initiator = true)
            } catch (error: Exception) {
                runCatching { socket?.close() }
                if (!closed.get()) listener.onFailure(mac, error.message ?: error.javaClass.simpleName)
            } finally {
                connecting.remove(mac)
            }
        }, "EveryPodsRelaySignalConnect").start()
    }

    fun send(macAddress: String, payload: ByteArray): Boolean {
        require(payload.size <= MAX_FRAME_BYTES) { "Relay signaling message exceeds the limit" }
        return links[macAddress.uppercase()]?.send(payload) ?: false
    }

    fun isConnected(macAddress: String): Boolean = links.containsKey(macAddress.uppercase())

    fun disconnect(macAddress: String) {
        val mac = macAddress.uppercase()
        connecting.remove(mac)
        pendingOpens.remove(mac)?.timeout?.cancel(false)
        links.remove(mac)?.close(notify = false)
    }

    private fun attach(socket: BluetoothSocket, initiator: Boolean) {
        val mac = socket.remoteDevice.address.uppercase()
        val link = SocketLink(mac, socket, initiator)
        val existing = links.putIfAbsent(mac, link)
        if (existing != null) {
            link.close(notify = false)
            return
        }
        listener.onPeerConnected(mac, initiator)
        Thread({ link.readLoop() }, "EveryPodsRelaySignalRead").start()
    }

    // --- Bluetooth LE signalling (RELAY records) ---

    private fun failPendingOpen(mac: String, id: Int, reason: String) {
        val pending = pendingOpens[mac]?.takeIf { it.id == id } ?: return
        if (!pendingOpens.remove(mac, pending)) return
        pending.timeout.cancel(false)
        connecting.remove(mac)
        Log.i(TAG, "[$mac] relay signaling open failed: $reason")
        if (!closed.get()) listener.onFailure(mac, reason)
    }

    /** Sends `kind | link id (u32 BE) | bytes`. Every record names its link, so a late record from an old one is ignored. */
    private fun sendRelay(mac: String, kind: Int, id: Int, bytes: ByteArray = ByteArray(0)): Boolean {
        val record = java.nio.ByteBuffer.allocate(5 + bytes.size).put(kind.toByte()).putInt(id).put(bytes).array()
        return PeerLeChannel.send(mac, PeerGattRecords.RELAY, record)
    }

    private fun onRelay(mac: String, payload: ByteArray) {
        if (closed.get() || payload.size < 5) return
        val kind = payload[0].toInt() and 0xFF
        val id = java.nio.ByteBuffer.wrap(payload, 1, 4).int
        val current = links[mac] as? LeLink
        when (kind) {
            KIND_OPEN -> onOpen(mac, id)
            KIND_OPEN_ACK -> {
                val pending = pendingOpens[mac]?.takeIf { it.id == id } ?: return
                if (!pendingOpens.remove(mac, pending)) return
                pending.timeout.cancel(false)
                connecting.remove(mac)
                links[mac]?.close(notify = false)
                val link = LeLink(mac, id, initiator = true)
                if (links.putIfAbsent(mac, link) != null) { link.close(notify = false); return }
                Log.i(TAG, "[$mac] relay signaling ready over Bluetooth LE as initiator")
                listener.onPeerConnected(mac, true)
            }
            KIND_DATA -> current?.takeIf { it.id == id }?.receive(payload.copyOfRange(5, payload.size))
            KIND_CLOSE -> {
                failPendingOpen(mac, id, "Bluetooth LE peer refused signaling")
                current?.takeIf { it.id == id }?.peerClosed()
            }
        }
    }

    private fun onOpen(mac: String, id: Int) {
        val allowed = PeerLeChannel.isReady(mac) && CrossDevice.configuredPeers.any { it.equals(mac, ignoreCase = true) }
        if (!allowed) { sendRelay(mac, KIND_CLOSE, id); return }
        // Both phones opened at once: the LE central's OPEN wins, the other phone answers it instead.
        if (pendingOpens.containsKey(mac)) {
            if (PeerLeChannel.isCentral(mac)) return
            pendingOpens.remove(mac)?.timeout?.cancel(false)
            connecting.remove(mac)
        }
        // A new OPEN means the peer dropped whatever link it had: replace ours instead of refusing it.
        (links[mac] as? LeLink)?.takeIf { it.id != id }?.let {
            Log.i(TAG, "[$mac] relay signaling: peer reopened; replacing the old link")
            it.close(notify = true, tellPeer = false)
        }
        if (links[mac] is SocketLink) { sendRelay(mac, KIND_CLOSE, id); return }
        val link = LeLink(mac, id, initiator = false)
        if (links.putIfAbsent(mac, link) != null) return
        sendRelay(mac, KIND_OPEN_ACK, id)
        Log.i(TAG, "[$mac] relay signaling ready over Bluetooth LE as responder")
        listener.onPeerConnected(mac, false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PeerLeChannel.unregister(PeerGattRecords.RELAY)
        PeerLeChannel.removeLinkListener(leLinkListener)
        pendingOpens.values.forEach { it.timeout.cancel(false) }
        pendingOpens.clear()
        runCatching { timer.shutdownNow() }
        runCatching { server?.close() }
        server = null
        links.values.toList().forEach { it.close(notify = false) }
        links.clear()
    }

    /** One signaling link, over an RFCOMM socket or the Bluetooth LE channel. */
    private abstract inner class Link(val mac: String, val initiator: Boolean) {
        abstract fun send(payload: ByteArray): Boolean
        abstract fun close(notify: Boolean = true)
    }

    private inner class LeLink(mac: String, val id: Int, initiator: Boolean) : Link(mac, initiator) {
        private val ended = AtomicBoolean(false)

        override fun send(payload: ByteArray): Boolean {
            if (ended.get() || closed.get()) return false
            if (sendRelay(mac, KIND_DATA, id, payload)) return true
            listener.onFailure(mac, "Bluetooth LE signaling write failed")
            close()
            return false
        }

        fun receive(payload: ByteArray) {
            if (ended.get() || closed.get()) return
            if (payload.size !in 1..MAX_FRAME_BYTES) {
                listener.onFailure(mac, "Invalid relay signaling frame size")
                close()
                return
            }
            listener.onMessage(mac, payload)
        }

        /** The peer sent CLOSE: end without answering it. */
        fun peerClosed() = end(notify = true, tellPeer = false)

        override fun close(notify: Boolean) = close(notify, tellPeer = true)

        fun close(notify: Boolean, tellPeer: Boolean) = end(notify, tellPeer)

        private fun end(notify: Boolean, tellPeer: Boolean) {
            if (!ended.compareAndSet(false, true)) return
            links.remove(mac, this)
            if (tellPeer) sendRelay(mac, KIND_CLOSE, id)
            if (notify && !closed.get()) listener.onPeerDisconnected(mac)
        }
    }

    private inner class SocketLink(mac: String, private val socket: BluetoothSocket, initiator: Boolean) : Link(mac, initiator) {
        private val ended = AtomicBoolean(false)
        private val output = DataOutputStream(socket.outputStream)
        private val input = DataInputStream(socket.inputStream)

        @Synchronized
        override fun send(payload: ByteArray): Boolean {
            if (ended.get() || closed.get()) return false
            return try {
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
                true
            } catch (error: IOException) {
                listener.onFailure(mac, error.message ?: "Bluetooth signaling write failed")
                close()
                false
            }
        }

        fun readLoop() {
            try {
                while (!ended.get() && !closed.get()) {
                    val length = input.readInt()
                    if (length !in 1..MAX_FRAME_BYTES) throw IOException("Invalid relay signaling frame size")
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    listener.onMessage(mac, payload)
                }
            } catch (error: Exception) {
                if (!ended.get() && !closed.get() && error !is java.io.EOFException) {
                    listener.onFailure(mac, error.message ?: error.javaClass.simpleName)
                }
            } finally {
                close()
            }
        }

        override fun close(notify: Boolean) {
            if (!ended.compareAndSet(false, true)) return
            runCatching { socket.close() }
            links.remove(mac, this)
            if (notify && !closed.get()) listener.onPeerDisconnected(mac)
        }
    }

    companion object {
        private const val TAG = "RelaySignalHub"
        private const val SERVICE_NAME = "EveryPodsRelaySignal"
        private const val MAX_FRAME_BYTES = 128 * 1024 + 64
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val KIND_OPEN = 1
        private const val KIND_OPEN_ACK = 2
        private const val KIND_DATA = 3
        private const val KIND_CLOSE = 4
        private val SERVICE_UUID: UUID = UUID.fromString("6d3b1a70-1e83-4a29-a327-1993fd1b7e91")
    }
}
