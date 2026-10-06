package io.automated.ventures.everypods.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnnouncementAudioAttributesTest {

    @Test
    fun audibleAssistantStreamKeepsAssistantUsage() {
        assertEquals(
            AudioAttributes.USAGE_ASSISTANT,
            AnnouncementAudioAttributes.chooseUsage(assistantStreamMuted = false, assistantStreamVolume = 5)
        )
    }

    @Test
    fun mutedAssistantStreamFallsBackToAccessibility() {
        // Pixel 10 / Android 17, Oct 6 2026: STREAM_ASSISTANT Muted:true streamVolume:0
        assertEquals(
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AnnouncementAudioAttributes.chooseUsage(assistantStreamMuted = true, assistantStreamVolume = 15)
        )
    }

    @Test
    fun zeroAssistantVolumeFallsBackToAccessibility() {
        assertEquals(
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AnnouncementAudioAttributes.chooseUsage(assistantStreamMuted = false, assistantStreamVolume = 0)
        )
    }

    @Test
    fun speechAttributesFollowAssistantStreamState() {
        val am = mockk<AudioManager>()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns am

        every { am.isStreamMute(AnnouncementAudioAttributes.STREAM_ASSISTANT) } returns true
        every { am.getStreamVolume(AnnouncementAudioAttributes.STREAM_ASSISTANT) } returns 0
        val silent = AnnouncementAudioAttributes.speech(context)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, silent.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, silent.contentType)

        every { am.isStreamMute(AnnouncementAudioAttributes.STREAM_ASSISTANT) } returns false
        every { am.getStreamVolume(AnnouncementAudioAttributes.STREAM_ASSISTANT) } returns 7
        assertEquals(AudioAttributes.USAGE_ASSISTANT, AnnouncementAudioAttributes.speech(context).usage)
    }

    @Test
    fun audioManagerFailureKeepsAssistantUsage() {
        val am = mockk<AudioManager>()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns am
        every { am.isStreamMute(any()) } throws IllegalArgumentException("bad stream")
        assertEquals(AudioAttributes.USAGE_ASSISTANT, AnnouncementAudioAttributes.usage(context))
    }

    @Test
    fun announcementSpeechDetectionCoversBothUsages() {
        assertTrue(AnnouncementAudioAttributes.isAnnouncementSpeech(
            AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertTrue(AnnouncementAudioAttributes.isAnnouncementSpeech(
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertFalse(AnnouncementAudioAttributes.isAnnouncementSpeech(
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertFalse(AnnouncementAudioAttributes.isAnnouncementSpeech(
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioAttributes.CONTENT_TYPE_SONIFICATION))
    }
}
