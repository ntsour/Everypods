/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import android.util.Log
import io.automated.ventures.everypods.utils.CrossDevice
import io.automated.ventures.everypods.utils.peer.PeerGattRecords
import io.automated.ventures.everypods.utils.peer.PeerLeChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Signaling link between two phones over the Bluetooth LE link ([PeerLeChannel]), carried in RELAY
 * records (OPEN / OPEN_ACK / DATA / CLOSE). Without a ready LE link, [connect] fails.
 */
class RelayBluetoothSignalHub(
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onPeerConnected(mac: String, initiator: Boolean)
        fun onMessage(mac: String, payload: ByteArray)
        fun onPeerDisconnected(mac: String)
        fun onFailure(mac: String?, reason: String)
    }

    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap<String, Link>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
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

    fun connect(macAddress: String) {
        check(!closed.get())
        val mac = macAddress.uppercase()
        require(CrossDevice.configuredPeers.any { it.equals(mac, ignoreCase = true) }) {
            "Connect only to a configured EveryPods peer"
        }
        if (links.containsKey(mac) || !connecting.add(mac)) return
        if (!PeerLeChannel.isReady(mac)) {
            connecting.remove(mac)
            listener.onFailure(mac, "Bluetooth LE link is not ready")
            return
        }
        val id = random.nextInt()
        pendingOpens.remove(mac)?.timeout?.cancel(false)
        pendingOpens[mac] = PendingOpen(id, timer.schedule({ failPendingOpen(mac, id, "Bluetooth LE peer did not answer") },
            OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        Log.i(TAG, "[$mac] relay signaling OPEN over Bluetooth LE (link ${id.toUInt().toString(16)})")
        if (!sendRelay(mac, KIND_OPEN, id)) failPendingOpen(mac, id, "Bluetooth LE write failed")
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
        links.values.toList().forEach { it.close(notify = false) }
        links.clear()
    }

    /** One signaling link over the Bluetooth LE channel. */
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

    companion object {
        private const val TAG = "RelaySignalHub"
        private const val MAX_FRAME_BYTES = 128 * 1024 + 64
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val KIND_OPEN = 1
        private const val KIND_OPEN_ACK = 2
        private const val KIND_DATA = 3
        private const val KIND_CLOSE = 4
    }
}
