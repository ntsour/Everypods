package io.automated.ventures.everypods.utils

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.services.CallNotifListener
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaControllerDetectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val main = shadowOf(Looper.getMainLooper())

    @Before fun initialize() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        ReflectionHelpers.setStaticField(MediaController::class.java, "audioManager", null)
        ReflectionHelpers.setStaticField(MediaController::class.java, "lastSelfActionAt", 0L)
        MediaController.initialize(audio, context.getSharedPreferences("settings", Context.MODE_PRIVATE), context)
        MediaController.iPausedTheMedia = false
        MediaController.setRelayAudioPlaybackActive(false)
        MediaController.onRelayHandoverSuppressionChanged(false, "test reset")
        MediaController.resetMusicActiveState()
        main.idleFor(Duration.ofSeconds(2))
        ShadowLog.clear()
    }

    private fun messages() = ShadowLog.getLogsForTag("MediaController").map { it.msg }

    @Test fun actualStartImmediatelyAfterEmptyCallbackSurvivesCoalescing() {
        shadowOf(audio).setIsMusicActive(false)
        MediaController.cb.onPlaybackConfigChanged(mutableListOf())
        main.idleFor(Duration.ofMillis(20))
        shadowOf(audio).setIsMusicActive(true)
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()))
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(100))
        // No Bluetooth service is created in this test. Reaching its request boundary
        // proves the start was detected rather than discarded by the old debounce.
        assertTrue(messages().any { it.contains("AirPods service unavailable; trigger=playback callback") })
    }

    @Test fun emptyPlaybackListStartsABoundedVerification() {
        shadowOf(audio).setIsMusicActive(true)
        MediaController.cb.onPlaybackConfigChanged(mutableListOf())
        main.idleFor(Duration.ofSeconds(2))
        assertTrue(messages().any { it.contains("Media verification ended: localActive=true") })
        assertFalse(messages().any { it.contains("requesting takeOver") })
        val count = messages().size
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(count, messages().size)
    }

    @Test fun speechReplacingMediaDuringCoalescingCancelsThePendingStart() {
        shadowOf(audio).setIsMusicActive(true)
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()))
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(20))
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()))
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofSeconds(2))
        assertFalse(messages().any { it.contains("AirPods service unavailable") || it.contains("requesting takeOver") })
    }

    @Test fun grantingAccessAfterInitializationAttachesAndRevokingDetachesSessions() {
        assertFalse(MediaController.mediaSessionAccessGranted.value)
        val session = MediaSession(context, "permission recovery")
        shadowOf(sessions).addController(session.controller)
        val component = ComponentName(context, CallNotifListener::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        context.contentResolver.notifyChange(Settings.Secure.getUriFor("enabled_notification_listeners"), null)
        main.idle()
        assertTrue(MediaController.mediaSessionAccessGranted.value)
        assertEquals(1, shadowOf(session.controller).callbacks.size)

        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        context.contentResolver.notifyChange(Settings.Secure.getUriFor("enabled_notification_listeners"), null)
        main.idle()
        assertFalse(MediaController.mediaSessionAccessGranted.value)
        assertTrue(shadowOf(session.controller).callbacks.isEmpty())
        session.release()
    }

    @Test fun anOldPauseRememberedFromHandoverDoesNotVetoAFreshUserSessionStart() {
        val session = MediaSession(context, "fresh user Play")
        val controller = session.controller
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
        shadowOf(sessions).addController(controller)
        val component = ComponentName(context, CallNotifListener::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        MediaController.refreshMediaSessionMonitoring(context)
        MediaController.iPausedTheMedia = true
        shadowOf(audio).setIsMusicActive(true)
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        shadowOf(controller).executeOnPlaybackStateChanged(controller.playbackState)
        main.idle()
        assertTrue(messages().any { it.contains("AirPods service unavailable; trigger=media session") })
        session.release()
    }

    @Test fun receiverStopKeepsContinuingAudioButAllowsTheNextPauseAndPlay() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        shadowOf(audio).setIsMusicActive(true)
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(attrs))
        MediaController.setRelayAudioPlaybackActive(true)
        MediaController.setRelayAudioPlaybackActive(false)
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(120))
        assertFalse(messages().any { it.contains("AirPods service unavailable") })

        shadowOf(audio).setIsMusicActive(false)
        MediaController.cb.onPlaybackConfigChanged(mutableListOf())
        main.idleFor(Duration.ofMillis(120))
        shadowOf(audio).setIsMusicActive(true)
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(120))
        assertTrue(messages().any { it.contains("AirPods service unavailable; trigger=playback callback") })
    }
    @Test fun peerYieldPausesUserStartedPlaybackAndStopsOldReplayWatch() {
        shadowOf(audio).setIsMusicActive(true)
        MediaController.userPlayedTheMedia = true
        MediaController.armPendingMusicTakeover("00:11:22:33:44:55")
        MediaController.restartRouteLandPoller()
        MediaController.yieldPlaybackToPeer()
        main.idle()
        assertTrue(messages().any { it.contains("DISPATCHING PAUSE KEY EVENT") })
        assertTrue(MediaController.iPausedTheMedia)
        shadowOf(audio).setIsMusicActive(false)
        main.idleFor(Duration.ofSeconds(20))
        assertFalse(messages().any { it.contains("re-issuing play") })
        assertFalse(messages().any { it.contains("Sending play with") })
    }

    @Test fun peerYieldBaselinesContinuingPlaybackButAllowsLaterFreshUserPlay() {
        shadowOf(audio).setIsMusicActive(true)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(attrs))
        MediaController.yieldPlaybackToPeer()
        main.idleFor(Duration.ofSeconds(2))
        ShadowLog.clear()
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(120))
        assertFalse(messages().any { it.contains("AirPods service unavailable") })
        shadowOf(audio).setIsMusicActive(false)
        MediaController.cb.onPlaybackConfigChanged(mutableListOf())
        main.idleFor(Duration.ofMillis(120))
        shadowOf(audio).setIsMusicActive(true)
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofMillis(120))
        assertTrue(messages().any { it.contains("AirPods service unavailable; trigger=playback callback") })
    }

    @Test fun decorativeUiVideoDoesNotTriggerAUserMediaTakeover() {
        val attrs = io.automated.ventures.everypods.presentation.overlays.UiVideoPlayback.attributes()
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, attrs.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attrs.contentType)
        assertFalse(MediaController.isUserMediaPlayback(attrs.usage, attrs.contentType))
        shadowOf(audio).setIsMusicActive(true)
        shadowOf(audio).setActivePlaybackConfigurationsFor(listOf(attrs))
        MediaController.cb.onPlaybackConfigChanged(audio.activePlaybackConfigurations.toMutableList())
        main.idleFor(Duration.ofSeconds(2))
        assertFalse(messages().any { it.contains("AirPods service unavailable") || it.contains("requesting takeOver") })
    }

    @Test fun obsoletePeerYieldCannotPauseANewerLocalIntent() {
        shadowOf(audio).setIsMusicActive(true)
        MediaController.yieldPlaybackToPeer(stillCurrent = { false })
        main.idle()
        assertFalse(messages().any { it.contains("DISPATCHING PAUSE KEY EVENT") })
    }

}
