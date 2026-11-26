package io.automated.ventures.everypods

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatGptVoiceLaunchActivityTest {
    @Test
    fun `voice intent opens the ChatGPT voice deep link in the ChatGPT app`() {
        val intent = chatGptVoiceIntent()

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://chat.com/c?mode=voice", intent.dataString)
        assertEquals("com.openai.chatgpt", intent.`package`)
        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
