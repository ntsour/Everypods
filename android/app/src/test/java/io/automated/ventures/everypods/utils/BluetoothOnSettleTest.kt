package io.automated.ventures.everypods.utils

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothOnSettleTest {

    @Test
    fun neverSettlingBeforeBluetoothTurnsOn() {
        assertFalse(BluetoothOnSettle().settling(1_000L))
    }

    @Test
    fun settlesOnlyInsideTheWindow() {
        val settle = BluetoothOnSettle(windowMs = 6_000L)
        settle.onTurnedOn(10_000L)
        assertTrue(settle.settling(10_000L))
        assertTrue(settle.settling(15_999L))
        assertFalse(settle.settling(16_000L))
        assertFalse(settle.settling(60_000L))
    }

    @Test
    fun windowCoversTheObservedMediaServiceStorm() {
        // 12:47:21.6 to 12:47:23.7 is about 2.1 s.
        assertTrue(BluetoothOnSettle.WINDOW_MS > 2_100L)
    }

    @Test
    fun takeoverIsRejectedFirstWhileBluetoothSettles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = MediaController::class.java.getDeclaredField("audioManager")
        field.isAccessible = true
        field.set(MediaController, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        ShadowLog.clear()

        MediaController.onBluetoothTurnedOn()
        val request = MediaController::class.java.getDeclaredMethod("requestMediaTakeover", String::class.java)
        request.isAccessible = true
        request.invoke(MediaController, "playback callback")

        val skipped = ShadowLog.getLogsForTag("MediaController").filter { it.type == Log.INFO }
        assertEquals(1, skipped.size)
        assertTrue(skipped[0].msg, skipped[0].msg.startsWith("Media handover skipped: Bluetooth just turned on"))
    }
}
