/*
    ProPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 ProPods contributors

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

package io.nikos.propods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nikos.propods.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class CrossDevicePackets(val packet: ByteArray) {
    AIRPODS_CONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x01)),
    AIRPODS_DISCONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
    REQUEST_DISCONNECT(byteArrayOf(0x00, 0x02, 0x00, 0x00)),
    REQUEST_BATTERY_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x01)),
    REQUEST_ANC_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x02)),
    REQUEST_CONNECTION_STATUS(byteArrayOf(0x00, 0x02, 0x00, 0x03)),
    REQUEST_HANDOVER(byteArrayOf(0x00, 0x02, 0x00, 0x04)),
    AIRPODS_DATA_HEADER(byteArrayOf(0x00, 0x04, 0x00, 0x01)),
}

object CrossDevice {
    private const val TAG = "CrossDevice"
    private val UUID_CROSS_DEVICE = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    var isEnabled: Boolean = false
    var isAvailable: Boolean = false  // true = AirPods are on the remote device, not us
    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()

    /** True when at least one RFCOMM client is connected to our server, or our client is connected. */
    val isServerClientConnected: Boolean get() = clientSockets.isNotEmpty()
    val isPeerConnected: Boolean get() = isServerClientConnected || CrossDeviceClient.isConnected

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<BluetoothSocket>()
    @Volatile private var isServerRunning: Boolean = false

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean("cross_device_enabled", false)
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

        val peerMac = prefs.getString("cross_device_peer_mac", null)
        if (!peerMac.isNullOrEmpty()) {
            CrossDeviceClient.start(adapter, peerMac)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServer(adapter: android.bluetooth.BluetoothAdapter) {
        if (isServerRunning) {
            Log.d(TAG, "Server already running, skipping start")
            return
        }
        isServerRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("ProPodsCrossDevice", UUID_CROSS_DEVICE)
                Log.d(TAG, "RFCOMM server listening")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to open RFCOMM server: ${e.message}")
                return@launch
            }
            // Accept loop — each client gets its own coroutine so multiple can be active at once
            while (true) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    Log.d(TAG, "Server socket closed: ${e.message}")
                    break
                }
                Log.d(TAG, "Client connected: ${socket.remoteDevice.address} (${clientSockets.size + 1} total)")
                clientSockets.add(socket)
                CoroutineScope(Dispatchers.IO).launch { handleClientConnection(socket) }
            }
        }
    }

    private fun handleClientConnection(socket: BluetoothSocket) {
        val addr = socket.remoteDevice.address
        val ctx = ServiceManager.getService()?.applicationContext
        ctx?.sendBroadcast(
            Intent("io.nikos.propods.AIRPODS_CONNECTED_REMOTELY").setPackage(ctx.packageName)
        )
        // Tell only this new client our current AirPods state
        sendToSocket(
            socket,
            if (ServiceManager.getService()?.isConnected() == true)
                CrossDevicePackets.AIRPODS_CONNECTED.packet
            else
                CrossDevicePackets.AIRPODS_DISCONNECTED.packet
        )

        val buffer = ByteArray(1024)
        while (true) {
            val bytes = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                Log.d(TAG, "Client $addr read error (disconnected): ${e.message}")
                break
            }
            if (bytes == -1) break
            processPacket(buffer.copyOf(bytes))
        }

        socket.runCatching { close() }
        clientSockets.remove(socket)
        Log.d(TAG, "Client $addr removed (${clientSockets.size} remaining)")

        if (clientSockets.isEmpty()) {
            isAvailable = false
            val appCtx = ServiceManager.getService()?.applicationContext
            appCtx?.sendBroadcast(
                Intent("io.nikos.propods.AIRPODS_DISCONNECTED_REMOTELY").setPackage(appCtx.packageName)
            )
        }
    }

    private fun processPacket(raw: ByteArray) {
        Log.d(TAG, "Received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                // Peer wants the AirPods — release them if we hold the connection
                Log.d(TAG, "Received REQUEST_HANDOVER from peer, releasing AirPods")
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) -> {
                // Mark that a peer is taking over so we apply cooldown appropriately
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) -> {
                isAvailable = true
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) -> {
                isAvailable = false
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_BATTERY_BYTES.packet) -> {
                sendRemotePacket(batteryBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_ANC_BYTES.packet) -> {
                sendRemotePacket(ancBytes)
            }
            raw.contentEquals(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet) -> {
                sendRemotePacket(
                    if (ServiceManager.getService()?.isConnected() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                )
            }
            raw.size >= 4 && raw.sliceArray(0..3)
                .contentEquals(CrossDevicePackets.AIRPODS_DATA_HEADER.packet) -> {
                isAvailable = true
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
                svc.sendBatteryNotification()
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
                        Intent("io.nikos.propods.cross_device_island").setPackage(svc.packageName)
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
        if (dead.isNotEmpty()) clientSockets.removeAll(dead)
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

    fun notifyConnected() {
        sendRemotePacket(CrossDevicePackets.AIRPODS_CONNECTED.packet)
        CrossDeviceClient.send(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    }
    fun notifyDisconnected() {
        sendRemotePacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
        CrossDeviceClient.send(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)
    }

    fun close() {
        CrossDeviceClient.stop()
        serverSocket?.runCatching { close() }
        clientSockets.forEach { it.runCatching { close() } }
        clientSockets.clear()
        serverSocket = null
        isAvailable = false
        isEnabled = false
        isServerRunning = false
    }

    @SuppressLint("MissingPermission")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("cross_device_enabled", enabled)
            .apply()
        if (enabled) {
            init(context)
        } else {
            close()
        }
    }
}
