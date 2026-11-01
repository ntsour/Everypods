package io.automated.ventures.everypods.bluetooth.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile

/** Encapsulates hidden profile APIs; callers own permissions, proxy lifetime and command fencing. */
object AndroidHiddenProfileCommands {
    fun setPolicy(proxy: BluetoothProfile, device: BluetoothDevice?, policy: Int) {
        proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.java)
            .invoke(proxy, device, policy)
    }

    fun connect(proxy: BluetoothProfile, device: BluetoothDevice?): Boolean? =
        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) as? Boolean

    fun disconnect(proxy: BluetoothProfile, device: BluetoothDevice?) {
        proxy.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java).apply { isAccessible = true }
            .invoke(proxy, device)
    }
}
