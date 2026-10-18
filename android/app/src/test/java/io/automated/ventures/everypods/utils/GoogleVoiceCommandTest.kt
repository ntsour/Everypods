package io.automated.ventures.everypods.utils

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoogleVoiceCommandTest {
    @Test
    fun `headset command targets Google and identifies the requesting AirPods`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val headset = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")

        GoogleVoiceCommand.launch(application, headset)

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VOICE_COMMAND, intent.action)
        assertEquals("com.google.android.googlequicksearchbox", intent.`package`)
        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(headset, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java))
        assertEquals(BluetoothProfile.HEADSET, intent.getIntExtra(BluetoothProfile.EXTRA_PROFILE, -1))
    }
}
