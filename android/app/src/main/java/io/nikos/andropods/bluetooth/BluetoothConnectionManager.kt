/*
    AndroPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 AndroPods contributors

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

package io.nikos.andropods.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.nikos.andropods.bluetooth.connection.ConnectionEngine
import io.nikos.andropods.bluetooth.connection.ConnectionState

/**
 * Process-global handle to the currently-active AirPods connection. Owns:
 *  - the live `BluetoothSocket` reference (legacy callers that pull bytes directly),
 *  - the [ConnectionState] [StateFlow] (sole source of truth for the UI),
 *  - the [ConnectionEngine] instance so the service can route connect calls to it.
 *
 * All mutation goes through the methods on this object — nothing else touches the
 * fields directly.
 */
object BluetoothConnectionManager {
    private const val TAG = "BluetoothConnectionManager"

    private var currentSocket: BluetoothSocket? = null
    private var currentDevice: BluetoothDevice? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var engineRef: ConnectionEngine? = null

    fun setCurrentConnection(socket: BluetoothSocket, device: BluetoothDevice) {
        currentSocket = socket
        currentDevice = device
        Log.d(TAG, "Current connection set to device: ${device.address}")
    }

    fun clearCurrentConnection() {
        try { currentSocket?.close() } catch (_: Exception) {}
        currentSocket = null
        currentDevice = null
    }

    fun getCurrentSocket(): BluetoothSocket? = currentSocket

    fun bindEngine(engine: ConnectionEngine) {
        engineRef = engine
    }

    fun engine(): ConnectionEngine? = engineRef

    /** Bridge from the engine's StateFlow into this manager's flow. Called once at engine setup. */
    internal fun publishState(state: ConnectionState) {
        _state.value = state
    }

    /** Cheap predicate for legacy call sites that just want a "is it up?" boolean. */
    fun isConnected(): Boolean = _state.value is ConnectionState.Connected
}
