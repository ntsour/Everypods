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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Receives trace files from the Windows client over a dedicated RFCOMM service,
 * separate from the handover channels so a transfer can never be mistaken for a
 * peer command. Listens only while [HandoverTrace] is recording, and accepts only
 * bonded devices. Files land in `handover-trace/windows/`, next to the Android
 * trace, so one `adb pull` collects both.
 *
 * Wire format, all integers unsigned 32-bit big-endian, repeated per file:
 *   client: nameLength, name (UTF-8), size, size bytes
 *   server: 1 byte, 0x01 stored / 0x00 rejected
 * A nameLength of 0 ends the session. Names must match [NAME_PATTERN]; a resent
 * file replaces the earlier copy (Windows traces only grow during a run).
 */
object TraceUploadReceiver {
    private const val TAG = "TraceUpload"
    val SERVICE_UUID: UUID = UUID.fromString("662d622f-1c89-4324-9f28-07d4fd521982")
    private val NAME_PATTERN = Regex("^windows-[0-9]{8}-[0-9]{6}-pid[0-9]{1,10}\\.log$")
    private const val MAX_NAME_BYTES = 128
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val SOCKET_IDLE_TIMEOUT_MS = 30_000L

    @Volatile private var thread: Thread? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var running = false

    fun directory(context: Context): File = File(HandoverTrace.directory(context), "windows")

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        thread = Thread({ listenLoop(app) }, "trace-upload").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        server?.runCatching { close() }
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun listenLoop(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        while (running) {
            try {
                if (!adapter.isEnabled) { Thread.sleep(10_000L); continue }
                val listener = adapter.listenUsingRfcommWithServiceRecord("EveryPodsTraceUpload", SERVICE_UUID)
                server = listener
                Log.i(TAG, "Listening for Windows trace uploads")
                listener.use {
                    while (running) {
                        val socket = listener.accept()
                        handle(context, socket)
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth permission missing; retrying in 30 s")
                sleepQuietly(30_000L)
            } catch (e: IOException) {
                // accept() fails when Bluetooth turns off or stop() closes the listener.
                if (running) {
                    Log.i(TAG, "Trace upload listener ended: ${e.message}; retrying in 10 s")
                    sleepQuietly(10_000L)
                }
            } finally {
                server = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(context: Context, socket: BluetoothSocket) {
        val peer = runCatching { socket.remoteDevice.address }.getOrNull()
        val bonded = runCatching { socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (!bonded) {
            Log.w(TAG, "Rejecting trace upload from unbonded device $peer")
            socket.runCatching { close() }
            return
        }
        // A stalled sender must not hold the socket forever.
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val watchdog = Thread {
            try {
                while (true) {
                    Thread.sleep(1_000L)
                    if (System.currentTimeMillis() - lastActivity.get() > SOCKET_IDLE_TIMEOUT_MS) {
                        Log.w(TAG, "Trace upload from $peer idle for $SOCKET_IDLE_TIMEOUT_MS ms; closing")
                        socket.runCatching { close() }
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        val touch = { lastActivity.set(System.currentTimeMillis()) }
        val dir = directory(context)
        var stored = 0
        try {
            socket.use {
                val input = DataInputStream(socket.inputStream.buffered())
                val output = DataOutputStream(socket.outputStream)
                Log.i(TAG, "Trace upload from $peer started")
                while (true) {
                    val nameLength = input.readInt()
                    touch()
                    if (nameLength == 0) break
                    require(nameLength in 1..MAX_NAME_BYTES) { "bad name length $nameLength" }
                    val name = ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8)
                    val size = input.readInt().toLong() and 0xffffffffL
                    val accepted = NAME_PATTERN.matches(name) && size <= MAX_FILE_BYTES &&
                        (dir.isDirectory || dir.mkdirs())
                    val partial = File(dir, "$name.partial")
                    if (accepted) partial.outputStream().use { out -> copy(input, out, size, touch) }
                    else copy(input, null, size, touch)
                    val ok = accepted && partial.renameTo(File(dir, name).also { it.delete() })
                    if (!ok) partial.delete()
                    output.writeByte(if (ok) 1 else 0)
                    output.flush()
                    if (ok) stored++
                    Log.i(TAG, "Trace upload from $peer: $name ($size bytes) ${if (ok) "stored" else "rejected"}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trace upload from $peer ended: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            watchdog.interrupt()
            Log.i(TAG, "Trace upload from $peer finished, $stored file(s) stored")
        }
    }

    private fun copy(input: DataInputStream, output: java.io.OutputStream?, size: Long, onProgress: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        var sinceProgress = 0L
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("stream ended with $remaining bytes left")
            output?.write(buffer, 0, n)
            remaining -= n
            sinceProgress += n
            if (sinceProgress >= 64 * 1024) { sinceProgress = 0; onProgress() }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }
}
