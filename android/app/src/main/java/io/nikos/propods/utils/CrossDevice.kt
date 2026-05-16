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

enum class CrossDevicePackets(val packet: ByteArray) {
    AIRPODS_CONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x01)),
    AIRPODS_DISCONNECTED(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
    REQUEST_DISCONNECT(byteArrayOf(0x00, 0x02, 0x00, 0x00)),
    REQUEST_BATTERY_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x01)),
    REQUEST_ANC_BYTES(byteArrayOf(0x00, 0x02, 0x00, 0x02)),
    REQUEST_CONNECTION_STATUS(byteArrayOf(0x00, 0x02, 0x00, 0x03)),
    AIRPODS_DATA_HEADER(byteArrayOf(0x00, 0x04, 0x00, 0x01)),
}

object CrossDevice {
    private const val TAG = "CrossDevice"
    private val UUID_CROSS_DEVICE = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    var isEnabled: Boolean = false
    var isAvailable: Boolean = false  // true = AirPods are on the remote device, not us
    var batteryBytes: ByteArray = byteArrayOf()
    var ancBytes: ByteArray = byteArrayOf()

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null

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
    }

    @SuppressLint("MissingPermission")
    private fun startServer(adapter: android.bluetooth.BluetoothAdapter) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("ProPodsCrossDevice", UUID_CROSS_DEVICE)
                Log.d(TAG, "RFCOMM server listening")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to open RFCOMM server: ${e.message}")
                return@launch
            }
            // Accept loop — restarts after each client disconnects
            while (true) {
                val socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    Log.d(TAG, "Server socket closed: ${e.message}")
                    break
                }
                Log.d(TAG, "Client connected: ${socket.remoteDevice.address}")
                clientSocket?.runCatching { close() }
                clientSocket = socket
                handleClientConnection(socket)  // blocks until client disconnects
            }
        }
    }

    private fun handleClientConnection(socket: BluetoothSocket) {
        val ctx = ServiceManager.getService()?.applicationContext
        ctx?.sendBroadcast(
            Intent("io.nikos.propods.AIRPODS_CONNECTED_REMOTELY").setPackage(ctx.packageName)
        )
        // Tell the remote side whether we currently hold the AirPods connection
        sendRemotePacket(
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
                Log.d(TAG, "Client read error (disconnected): ${e.message}")
                break
            }
            if (bytes == -1) break
            processPacket(buffer.copyOf(bytes))
        }

        socket.runCatching { close() }
        clientSocket = null
        isAvailable = false

        val appCtx = ServiceManager.getService()?.applicationContext
        appCtx?.sendBroadcast(
            Intent("io.nikos.propods.AIRPODS_DISCONNECTED_REMOTELY").setPackage(appCtx.packageName)
        )
        // Loop in startServer will automatically call accept() again for the next client
    }

    private fun processPacket(raw: ByteArray) {
        Log.d(TAG, "Received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) -> {
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
        val socket = clientSocket ?: return
        try {
            socket.outputStream.write(data)
            socket.outputStream.flush()
            Log.d(TAG, "Sent: ${data.joinToString("") { "%02x".format(it) }}")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send packet: ${e.message}")
        }
    }

    fun notifyConnected() = sendRemotePacket(CrossDevicePackets.AIRPODS_CONNECTED.packet)
    fun notifyDisconnected() = sendRemotePacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet)

    fun close() {
        serverSocket?.runCatching { close() }
        clientSocket?.runCatching { close() }
        serverSocket = null
        clientSocket = null
        isAvailable = false
    }
}
