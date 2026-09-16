package io.automated.ventures.everypods.utils

import android.media.AudioAttributes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaControllerUserMediaTest {

    @Test
    fun mediaUsageQualifiesAsUserMedia() {
        assertTrue(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.CONTENT_TYPE_UNKNOWN
            )
        )
    }

    @Test
    fun musicAndMovieContentQualify() {
        assertTrue(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.CONTENT_TYPE_MUSIC
            )
        )
        assertTrue(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.CONTENT_TYPE_MOVIE
            )
        )
    }

    @Test
    fun assistantSpeechAnnouncementIsNotUserMedia() {
        assertFalse(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.CONTENT_TYPE_SPEECH
            )
        )
    }

    @Test
    fun accessibilityAndSonificationAreNotUserMedia() {
        assertFalse(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                AudioAttributes.CONTENT_TYPE_SPEECH
            )
        )
        assertFalse(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
            )
        )
    }

    @Test
    fun bareSpeechWithoutMediaUsageIsNotUserMedia() {
        assertFalse(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.CONTENT_TYPE_SPEECH
            )
        )
    }

    @Test
    fun podcastSpeechWithMediaUsageStillQualifies() {
        assertTrue(
            MediaController.isUserMediaPlayback(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.CONTENT_TYPE_SPEECH
            )
        )
    }
}
