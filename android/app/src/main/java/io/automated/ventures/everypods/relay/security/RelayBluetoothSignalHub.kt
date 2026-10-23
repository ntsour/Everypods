/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.automated.ventures.everypods.utils.CrossDevice
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Length-framed signaling link over the already-bonded Bluetooth ACL. */
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

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startListening() {
        check(!closed.get())
        if (server != null || serverStarting) return
        serverStarting = true
        Thread({
            try {
                val socketServer = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
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
        Thread({
            var socket: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                socket = adapter.getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
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
        links.remove(mac)?.close(notify = false)
    }

    private fun attach(socket: BluetoothSocket, initiator: Boolean) {
        val mac = socket.remoteDevice.address.uppercase()
        val link = Link(mac, socket, initiator)
        val existing = links.putIfAbsent(mac, link)
        if (existing != null) {
            link.close(notify = false)
            return
        }
        listener.onPeerConnected(mac, initiator)
        Thread({ link.readLoop() }, "EveryPodsRelaySignalRead").start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        links.values.toList().forEach { it.close(notify = false) }
        links.clear()
    }

    private inner class Link(private val mac: String, private val socket: BluetoothSocket, val initiator: Boolean) {
        private val ended = AtomicBoolean(false)
        private val output = DataOutputStream(socket.outputStream)
        private val input = DataInputStream(socket.inputStream)

        @Synchronized
        fun send(payload: ByteArray): Boolean {
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

        fun close(notify: Boolean = true) {
            if (!ended.compareAndSet(false, true)) return
            runCatching { socket.close() }
            links.remove(mac, this)
            if (notify && !closed.get()) listener.onPeerDisconnected(mac)
        }
    }

    companion object {
        private const val SERVICE_NAME = "EveryPodsRelaySignal"
        private const val MAX_FRAME_BYTES = 128 * 1024 + 64
        private val SERVICE_UUID: UUID = UUID.fromString("6d3b1a70-1e83-4a29-a327-1993fd1b7e91")
    }
}
