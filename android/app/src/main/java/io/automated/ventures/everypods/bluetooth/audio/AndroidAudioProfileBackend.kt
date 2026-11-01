package io.automated.ventures.everypods.bluetooth.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context

/** Android bridge; public profile state is queried before hidden connect is invoked. */
@SuppressLint("MissingPermission")
class AndroidAudioProfileBackend(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) : AudioProfileAdapter.Backend {
    override fun acquire(callback: (AudioProfileAdapter.Proxy?) -> Unit): Boolean =
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.A2DP) {
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        callback(null)
                        return
                    }
                    callback(object : AudioProfileAdapter.Proxy {
                        override fun state(headset: String): AudioConnectionCoordinator.Profile =
                            when (proxy.getConnectionState(adapter.getRemoteDevice(headset))) {
                                BluetoothProfile.STATE_DISCONNECTED -> AudioConnectionCoordinator.Profile.DISCONNECTED
                                BluetoothProfile.STATE_CONNECTING -> AudioConnectionCoordinator.Profile.CONNECTING
                                BluetoothProfile.STATE_CONNECTED -> AudioConnectionCoordinator.Profile.CONNECTED
                                BluetoothProfile.STATE_DISCONNECTING -> AudioConnectionCoordinator.Profile.DISCONNECTING
                                else -> AudioConnectionCoordinator.Profile.UNKNOWN
                            }
                        override fun connect(headset: String): Boolean? {
                            val device = adapter.getRemoteDevice(headset)
                            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                AndroidHiddenProfileCommands.setPolicy(proxy, device, 100)
                            }
                            return AndroidHiddenProfileCommands.connect(proxy, device)
                        }
                        override fun close() = adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    })
                }
                override fun onServiceDisconnected(profile: Int) { callback(null) }
            }, BluetoothProfile.A2DP)
        } catch (_: SecurityException) { false }
}
