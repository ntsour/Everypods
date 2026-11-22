/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sends and receives the non-legacy records (V2, CASE, RELAY, …) of the phone to phone Bluetooth LE link,
 * whichever side ([PeerGattServer] or [PeerGattClient]) holds the link. Payloads above one record go out as
 * [PeerGattRecords.FRAG] records and are reassembled before dispatch ([PeerLeFragments]).
 */
object PeerLeChannel {
    /** The GATT sides, replaceable in tests. */
    interface Transport {
        fun isServerReady(mac: String): Boolean
        fun isClientReady(mac: String): Boolean
        fun sendViaServer(mac: String, type: Int, payload: ByteArray): Boolean
        fun sendViaClient(mac: String, type: Int, payload: ByteArray): Boolean
    }

    interface LinkListener {
        fun onLinkUp(mac: String, central: Boolean)
        fun onLinkDown(mac: String)
    }

    private const val TAG = "PeerLeChannel"

    private val gatt = object : Transport {
        override fun isServerReady(mac: String) = PeerGattServer.isReady(mac)
        override fun isClientReady(mac: String) = PeerGattClient.isReady(mac)
        override fun sendViaServer(mac: String, type: Int, payload: ByteArray) = PeerGattServer.sendRecord(mac, type, payload)
        override fun sendViaClient(mac: String, type: Int, payload: ByteArray) = PeerGattClient.sendRecord(mac, type, payload)
    }

    @Volatile internal var transport: Transport = gatt
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** True if [mac] is a configured, coordination-authorized peer. Set by CrossDevice. */
    @Volatile var authorizer: (String) -> Boolean = { false }

    private val handlers = ConcurrentHashMap<Int, (String, ByteArray) -> Unit>()
    private val linkListeners = CopyOnWriteArrayList<LinkListener>()
    private val reassembly = ConcurrentHashMap<String, PeerLeFragments>()
    private val counters = ConcurrentHashMap<String, Int>()

    /** Registers the handler for records of [type] (replaces an earlier one). Runs on the GATT delivery thread. */
    fun register(type: Int, handler: (mac: String, payload: ByteArray) -> Unit) { handlers[type] = handler }

    fun unregister(type: Int) { handlers.remove(type) }

    fun addLinkListener(listener: LinkListener) { linkListeners.addIfAbsent(listener) }

    fun removeLinkListener(listener: LinkListener) { linkListeners.remove(listener) }

    /** True if an LE link to [mac] is ready and [mac] is an authorized peer. */
    fun isReady(mac: String): Boolean {
        val key = mac.uppercase()
        return (transport.isServerReady(key) || transport.isClientReady(key)) && authorizer(key)
    }

    /** True if this phone is the LE central of its link to [mac] (it dialled the peer). */
    fun isCentral(mac: String): Boolean = transport.isClientReady(mac.uppercase())

    /**
     * Sends one record, fragmenting a payload above [PeerGattRecords.MAX_PAYLOAD]. False if no link is ready
     * or the payload exceeds [PeerLeFragments.MAX_MESSAGE].
     */
    fun send(mac: String, type: Int, payload: ByteArray): Boolean {
        val key = mac.uppercase()
        if (payload.size <= PeerGattRecords.MAX_PAYLOAD) return route(key, type, payload)
        if (!(transport.isServerReady(key) || transport.isClientReady(key))) return false
        val fragments = PeerLeFragments.split(type, nextMsgId(key), payload) ?: return false
        return fragments.all { route(key, PeerGattRecords.FRAG, it) }
    }

    private fun route(mac: String, type: Int, payload: ByteArray): Boolean =
        (transport.isServerReady(mac) && transport.sendViaServer(mac, type, payload)) ||
            (transport.isClientReady(mac) && transport.sendViaClient(mac, type, payload))

    private fun nextMsgId(mac: String): Int = counters.compute(mac) { _, v -> ((v ?: 0) + 1) and 0xFFFF }!!

    /** Entry point for both GATT listeners. */
    fun onRecord(mac: String, type: Int, payload: ByteArray) {
        val key = mac.uppercase()
        if (type == PeerGattRecords.FRAG) {
            val buffer = synchronized(reassembly) { reassembly.getOrPut(key) { PeerLeFragments() } }
            val result = synchronized(buffer) { buffer.accept(payload, clock()) }
            when (result) {
                is PeerLeFragments.Result.Complete -> dispatch(key, result.innerType, result.payload)
                is PeerLeFragments.Result.Rejected -> Log.d(TAG, "[$key] fragment dropped (${result.reason})")
                PeerLeFragments.Result.Pending -> Unit
            }
            return
        }
        dispatch(key, type, payload)
    }

    private fun dispatch(mac: String, type: Int, payload: ByteArray) {
        val handler = handlers[type] ?: return
        runCatching { handler(mac, payload) }.onFailure { Log.w(TAG, "[$mac] handler for $type failed", it) }
    }

    /** An LE link to [mac] became ready ([central]: this phone dialled it). */
    fun onLinkUp(mac: String, central: Boolean) {
        val key = mac.uppercase()
        reassembly.remove(key)
        linkListeners.forEach { runCatching { it.onLinkUp(key, central) } }
    }

    /** The LE link to [mac] ended. */
    fun onLinkDown(mac: String) {
        val key = mac.uppercase()
        reassembly.remove(key)
        counters.remove(key)
        linkListeners.forEach { runCatching { it.onLinkDown(key) } }
    }

    internal fun resetForTest() {
        transport = gatt
        clock = { SystemClock.elapsedRealtime() }
        authorizer = { false }
        handlers.clear(); linkListeners.clear(); reassembly.clear(); counters.clear()
    }
}
