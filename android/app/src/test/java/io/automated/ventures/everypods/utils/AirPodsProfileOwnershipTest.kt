package io.automated.ventures.everypods.utils

import android.bluetooth.BluetoothProfile
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AirPodsProfileOwnershipTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val address = "00:11:22:33:44:55"
    private val prefs get() = AudioLeasePrefs.prefs(context)
    @Before fun setup() {
        prefs.edit().clear().putString("mac_address", address).apply()
        CrossDevice.isEnabled = false
        CrossDevice.isAvailable = false
    }

    private fun send(state: Int) = AudioLeasePrefs.observeA2dpState(prefs, state)

    @Test fun `connecting then failing does not claim or replace last holder`() {
        send(BluetoothProfile.STATE_CONNECTING)
        assertFalse(AudioLeasePrefs.leaseEverSet(prefs))
        send(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(AudioLeasePrefs.leaseEverSet(prefs))
    }

    @Test fun `only completed connection claims ownership and disconnect remembers it`() {
        send(BluetoothProfile.STATE_CONNECTING)
        assertFalse(AudioLeasePrefs.isLeaseHolder(prefs))
        send(BluetoothProfile.STATE_CONNECTED)
        assertTrue(AudioLeasePrefs.isLeaseHolder(prefs))
        send(BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(AudioLeasePrefs.isLeaseHolder(prefs))
    }
}
