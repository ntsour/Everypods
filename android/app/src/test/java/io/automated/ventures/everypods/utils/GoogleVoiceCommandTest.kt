package io.automated.ventures.everypods.utils

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GoogleVoiceCommandTest {
    @Test
    fun `long press headset hook builds down, long press repeat and up with one downTime`() {
        val events = GoogleVoiceCommand.longPressHeadsetHook(1_000L)

        assertEquals(3, events.size)
        events.forEach {
            assertEquals(KeyEvent.KEYCODE_HEADSETHOOK, it.keyCode)
            assertEquals(1_000L, it.downTime)
        }
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(0, events[0].repeatCount)
        assertEquals(0, events[0].flags and KeyEvent.FLAG_LONG_PRESS)
        assertEquals(KeyEvent.ACTION_DOWN, events[1].action)
        assertEquals(1, events[1].repeatCount)
        assertEquals(KeyEvent.FLAG_LONG_PRESS, events[1].flags and KeyEvent.FLAG_LONG_PRESS)
        assertEquals(KeyEvent.ACTION_UP, events[2].action)
    }

    @Test
    fun `launch dispatches the headset long press and starts no activity`() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        GoogleVoiceCommand.launch(application)

        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dispatched = shadowOf(audioManager).dispatchedMediaKeyEvents
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
            dispatched.map { it.action },
        )
        assertEquals(listOf(0, 1), dispatched.take(2).map { it.repeatCount })
        assertEquals(0, dispatched[0].flags and KeyEvent.FLAG_LONG_PRESS)
        assertEquals(KeyEvent.FLAG_LONG_PRESS, dispatched[1].flags and KeyEvent.FLAG_LONG_PRESS)
        dispatched.forEach { assertEquals(KeyEvent.KEYCODE_HEADSETHOOK, it.keyCode) }
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
