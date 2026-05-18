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
    along with this program, if not, see <https://www.gnu.org/licenses/>.
*/

package io.nikos.propods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import io.nikos.propods.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

// RFCOMM client used when this device acts as the secondary (tablet/client) in an
// Android-to-Android handover. Connects to the peer's RFCOMM server and exchanges
// the same 4-byte packet protocol used by the Windows tray client.
object CrossDeviceClient {
    private const val TAG = "CrossDeviceClient"
    private val RFCOMM_UUID = UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile var isConnected: Boolean = false
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(adapter: BluetoothAdapter, peerMac: String) {
        if (running) return
        running = true
        job = CoroutineScope(Dispatchers.IO).launch {
            var backoff = 1_500L
            while (running) {
                try {
                    val device = adapter.getRemoteDevice(peerMac)
                    val s = device.createRfcommSocketToServiceRecord(RFCOMM_UUID)
                    s.connect()
                    socket = s
                    isConnected = true
                    backoff = 1_500L
                    Log.d(TAG, "Connected to peer $peerMac")

                    val svc = ServiceManager.getService()
                    val announcement = if (svc?.isConnected() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                    s.outputStream.write(announcement)
                    s.outputStream.flush()

                    val buf = ByteArray(1024)
                    while (running) {
                        val n = try {
                            s.inputStream.read(buf)
                        } catch (e: IOException) {
                            Log.d(TAG, "Read error (peer disconnected): ${e.message}")
                            break
                        }
                        if (n == -1) break
                        processPacket(buf.copyOf(n))
                    }
                    s.runCatching { close() }
                    socket = null
                    isConnected = false
                    Log.d(TAG, "Disconnected from peer, will retry")
                } catch (e: Exception) {
                    Log.d(TAG, "Connect failed (${e.message}), retrying in ${backoff}ms")
                    socket?.runCatching { close() }
                    socket = null
                    isConnected = false
                }
                if (running) {
                    delay(backoff)
                    backoff = (backoff * 1.5).toLong().coerceAtMost(15_000L)
                }
            }
        }
    }

    private fun processPacket(raw: ByteArray) {
        Log.d(TAG, "Received: ${raw.joinToString("") { "%02x".format(it) }}")
        when {
            raw.contentEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet) ->
                ServiceManager.getService()?.disconnectForCD()
            raw.contentEquals(CrossDevicePackets.REQUEST_HANDOVER.packet) -> {
                ServiceManager.getService()?.markPeerTakeoverAttempt()
                ServiceManager.getService()?.disconnectForCD()
            }
            raw.contentEquals(CrossDevicePackets.AIRPODS_CONNECTED.packet) ->
                CrossDevice.isAvailable = true
            raw.contentEquals(CrossDevicePackets.AIRPODS_DISCONNECTED.packet) ->
                CrossDevice.isAvailable = false
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_ACTIVE.packet) ->
                CrossDevice.peerAudioActive = true
            raw.contentEquals(CrossDevicePackets.WINDOWS_AUDIO_IDLE.packet) ->
                CrossDevice.peerAudioActive = false
        }
    }

    fun send(data: ByteArray) {
        if (data.isEmpty()) return
        val s = socket ?: return
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
            Log.d(TAG, "Sent: ${data.joinToString("") { "%02x".format(it) }}")
        } catch (e: IOException) {
            Log.w(TAG, "Send failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        isConnected = false
        job?.cancel()
        job = null
        socket?.runCatching { close() }
        socket = null
    }
}
