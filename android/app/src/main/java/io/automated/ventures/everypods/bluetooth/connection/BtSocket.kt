package io.automated.ventures.everypods.bluetooth.connection

import java.io.InputStream
import java.io.OutputStream

/**
 * Thin seam over [android.bluetooth.BluetoothSocket] so the connection engine can be
 * driven by a fake in unit tests. Production implementation lives in
 * [io.automated.ventures.everypods.bluetooth.connection.AndroidBtSocket].
 */
interface BtSocket {
    suspend fun connect()
    fun close()
    val isConnected: Boolean
    val inputStream: InputStream
    val outputStream: OutputStream
}

interface BtSocketFactory {
    fun create(): BtSocket
}

interface BluetoothAdapterState {
    fun isEnabled(): Boolean
}
