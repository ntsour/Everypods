package io.automated.ventures.everypods.bluetooth.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidHiddenProfileCommandsTest {
    class FakeProfile : BluetoothProfile {
        var lastDevice: BluetoothDevice? = null
        var policy: Int? = null
        var disconnected = false
        override fun getConnectedDevices(): MutableList<BluetoothDevice> = mutableListOf()
        override fun getDevicesMatchingConnectionStates(states: IntArray?): MutableList<BluetoothDevice> = mutableListOf()
        override fun getConnectionState(device: BluetoothDevice?): Int = BluetoothProfile.STATE_DISCONNECTED
        fun setConnectionPolicy(device: BluetoothDevice?, value: Int): Boolean { lastDevice = device; policy = value; return true }
        fun connect(device: BluetoothDevice?): Boolean { lastDevice = device; return true }
        fun disconnect(device: BluetoothDevice?): Boolean { lastDevice = device; disconnected = true; return true }
    }

    @Test fun `profile commands preserve device policy and boolean result`() {
        val profile = FakeProfile()
        val device = mockk<BluetoothDevice>()
        AndroidHiddenProfileCommands.setPolicy(profile, device, 100)
        assertSame(device, profile.lastDevice)
        assertEquals(100, profile.policy)
        assertEquals(true, AndroidHiddenProfileCommands.connect(profile, device))
        AndroidHiddenProfileCommands.disconnect(profile, device)
        assertTrue(profile.disconnected)
        assertSame(device, profile.lastDevice)
    }
}
