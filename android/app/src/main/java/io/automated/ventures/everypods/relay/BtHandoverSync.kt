/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.utils.CrossDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A bounded setup exchange, independent of peer membership and Wi-Fi relay authorization. */
object BtHandoverSync {
    private const val TAG = "BtHandoverSync"
    private val SERVICE_UUID = UUID.fromString("de51a5b3-4140-4fb7-a524-967379181773")
    private const val MAX_MESSAGE_BYTES = 1024
    private const val EXCHANGE_TIMEOUT_MS = 10_000L
    private val membershipLock = Any()
    @Volatile private var worker: Worker? = null

    @Synchronized fun start(context: Context) {
        if (worker == null) worker = Worker(context.applicationContext).also { it.start() }
        worker?.wake()
    }

    fun wake(address: String) { worker?.wake(address) }

    @Synchronized fun close() {
        worker?.close()
        worker = null
    }

    internal fun updateMembership(context: Context, address: String, keep: Boolean): Boolean = synchronized(membershipLock) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = preferences.getString("cross_device_peers", null)
        val peers = saved?.let(::JSONArray) ?: JSONArray().apply {
            preferences.getString("cross_device_peer_mac", null)?.takeIf(String::isNotBlank)?.let { put(it) }
        }
        val existing = (0 until peers.length()).map { peers.getString(it) }
        if (existing.any { it.equals(address, true) } == keep) return@synchronized false
        val updated = if (keep) existing + address.uppercase() else existing.filterNot { it.equals(address, true) }
        check(preferences.edit().putString("cross_device_peers", JSONArray(updated).toString())
            .remove("cross_device_peer_mac").commit()) { "Could not save peer membership" }
        true
    }

    @SuppressLint("MissingPermission")
    private class Worker(private val context: Context) {
        private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        private val store = DeviceConnectionStore(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val wakes = Channel<Unit>(Channel.CONFLATED)
        private val activeSockets = ConcurrentHashMap.newKeySet<BluetoothSocket>()
        private val inFlight = ConcurrentHashMap.newKeySet<String>()
        private val retryAt = ConcurrentHashMap<String, Long>()
        private val failures = ConcurrentHashMap<String, Int>()
        @Volatile private var listener: BluetoothServerSocket? = null
        private var listenerJob: Job? = null

        fun wake(address: String? = null) {
            address?.uppercase()?.let { retryAt.remove(it); failures.remove(it) }
            wakes.trySend(Unit)
        }

        fun start() {
            scope.launch {
                while (isActive) {
                    if (runCatching { adapter?.isEnabled == true }.getOrDefault(false)) {
                        ensureListener()
                        store.pendingHandoverChanges().forEach { (address, change) ->
                            if (inFlight.size >= 2 || SystemClock.elapsedRealtime() < (retryAt[address] ?: 0L) || !inFlight.add(address)) return@forEach
                            scope.launch {
                                try {
                                    reconcileMembership(address)
                                    exchange(address, change)
                                    failures.remove(address)
                                    retryAt.remove(address)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    val count = (failures[address] ?: 0).coerceAtMost(4) + 1
                                    failures[address] = count
                                    retryAt[address] = SystemClock.elapsedRealtime() + minOf(60_000L, 5_000L shl (count - 1))
                                    Log.i(TAG, "BT setup pending for $address: ${error.message}")
                                } finally {
                                    inFlight.remove(address)
                                    wakes.trySend(Unit)
                                }
                            }
                        }
                    } else {
                        listener?.runCatching { close() }
                        activeSockets.toList().forEach { it.runCatching { close() } }
                    }
                    withTimeoutOrNull(5_000L) { wakes.receive() }
                }
            }
        }

        private fun ensureListener() {
            if (listenerJob?.isActive == true) return
            listenerJob = scope.launch {
                try {
                    // Secure RFCOMM authenticates the Android Bluetooth bond.
                    val server = adapter!!.listenUsingRfcommWithServiceRecord("EveryPodsBTHandoverSetup", SERVICE_UUID)
                    listener = server
                    server.use {
                        while (isActive) {
                            val socket = server.accept()
                            if (socket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED || activeSockets.size >= 4) {
                                socket.close()
                                continue
                            }
                            activeSockets.add(socket)
                            if (!isActive) { socket.close(); activeSockets.remove(socket); break }
                            scope.launch {
                                boundedExchange(socket) {
                                    val address = socket.remoteDevice.address.uppercase()
                                    val request = read(socket)
                                    val winner = store.mergeRemoteHandover(address, request)
                                    reconcileMembership(address)
                                    write(socket, winner)
                                    Log.i(TAG, "Peer BT handover ${if (winner.enabled) "enabled" else "removed"} for $address revision=${winner.revision}")
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (scope.isActive) Log.i(TAG, "BT setup listener waiting: ${error.message}")
                } finally {
                    listener = null
                }
            }
        }

        private fun exchange(address: String, change: HandoverSyncState) {
            val device = adapter!!.getRemoteDevice(address)
            check(device.bondState == BluetoothDevice.BOND_BONDED) { "Pair the Android devices in Bluetooth settings first" }
            val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            activeSockets.add(socket)
            val timeout = scope.launch { delay(EXCHANGE_TIMEOUT_MS); socket.runCatching { close() } }
            try {
                socket.use {
                    check(scope.isActive) { "BT setup stopped" }
                    socket.connect()
                    write(socket, change)
                    val acknowledged = read(socket)
                    store.mergeRemoteHandover(address, acknowledged)
                    reconcileMembership(address)
                    Log.i(TAG, "BT handover synchronized for $address revision=${acknowledged.revision}")
                }
            } finally {
                timeout.cancel()
                activeSockets.remove(socket)
            }
        }

        private fun boundedExchange(socket: BluetoothSocket, exchange: () -> Unit) {
            val timeout = scope.launch { delay(EXCHANGE_TIMEOUT_MS); socket.runCatching { close() } }
            try { socket.use { exchange() } }
            catch (error: Exception) { if (scope.isActive) Log.i(TAG, "BT setup request failed: ${error.message}") }
            finally { timeout.cancel(); activeSockets.remove(socket) }
        }

        private fun reconcileMembership(address: String) {
            if (store.has(address, DeviceConnectionType.BT_HANDOVER)) {
                val added = updateMembership(context, address, keep = true)
                if (added || !CrossDevice.isConnectedTo(address)) CrossDevice.init(context)
            } else if (!store.has(address, DeviceConnectionType.WIFI_RELAY) && address.uppercase() !in store.pendingRevocations()) {
                CrossDevice.removePeerTransport(address)
                if (updateMembership(context, address, keep = false)) CrossDevice.init(context)
            }
        }

        fun close() {
            scope.cancel()
            listener?.runCatching { close() }
            activeSockets.toList().forEach { it.runCatching { close() } }
            activeSockets.clear()
            wakes.close()
        }
    }

    private fun read(socket: BluetoothSocket): HandoverSyncState {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..MAX_MESSAGE_BYTES) { "Invalid BT setup message length" }
        val bytes = ByteArray(length).also(input::readFully)
        return HandoverSyncState.decode(bytes.toString(Charsets.UTF_8))
    }

    private fun write(socket: BluetoothSocket, state: HandoverSyncState) {
        val bytes = state.encode().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_MESSAGE_BYTES)
        DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
    }
}
