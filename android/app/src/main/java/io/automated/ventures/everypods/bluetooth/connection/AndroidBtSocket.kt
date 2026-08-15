package io.automated.ventures.everypods.bluetooth.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Real [BtSocket] impl wrapping the reflective L2CAP [BluetoothSocket]. Keeps the
 * private-API ugliness contained here so the engine and tests stay clean.
 */
class AndroidBtSocket(
    private val socket: BluetoothSocket,
) : BtSocket {
    override suspend fun connect() = withContext(Dispatchers.IO) {
        socket.connect()
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    override val isConnected: Boolean
        get() = socket.isConnected

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream
}

class AndroidBtSocketFactory(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val uuid: ParcelUuid,
) : BtSocketFactory {

    override fun create(): BtSocket = AndroidBtSocket(createReflective())

    private fun createReflective(): BluetoothSocket {
        val type = 3 // L2CAP
        val constructorSpecs = listOf(
            arrayOf(adapter, device, type, true, true, 0x1001, uuid), // A16QPR3
            arrayOf(device, type, true, true, 0x1001, uuid),
            arrayOf(device, type, 1, true, true, 0x1001, uuid),
            arrayOf(type, 1, true, true, device, 0x1001, uuid),
            arrayOf(type, true, true, device, 0x1001, uuid)
        )
        var lastException: Exception? = null
        for ((index, params) in constructorSpecs.withIndex()) {
            try {
                val paramTypes = params.map { it::class.javaPrimitiveType ?: it::class.java }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*paramTypes)
                constructor.isAccessible = true
                Log.d(TAG, "Using BluetoothSocket constructor signature #${index + 1}")
                return constructor.newInstance(*params) as BluetoothSocket
            } catch (e: Exception) {
                Log.e(TAG, "Constructor signature #${index + 1} failed: ${e.message}")
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("No BluetoothSocket constructor matched")
    }

    companion object { private const val TAG = "AndroidBtSocketFactory" }
}

class AndroidBluetoothAdapterState(private val adapter: BluetoothAdapter) : BluetoothAdapterState {
    override fun isEnabled(): Boolean = adapter.isEnabled
}
