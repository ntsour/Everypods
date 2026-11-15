package io.automated.ventures.everypods.utils

import android.media.AudioManager
import android.view.KeyEvent
import io.automated.ventures.everypods.relay.RelayPlaybackCommand
import io.automated.ventures.everypods.relay.RelayPlaybackCommand.Action
import io.automated.ventures.everypods.relay.RelayPreviewService
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaControllerRelayRoutingTest {
    private val forwarded = mutableListOf<RelayPlaybackCommand>()
    private val audio = mockk<AudioManager>(relaxed = true)

    @Before fun setup() {
        ReflectionHelpers.setStaticField(MediaController::class.java, "audioManager", audio)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = true
        every { audio.isMusicActive } returns true
        mockkObject(RelayPreviewService.Companion)
        every { RelayPreviewService.forwardReceiverPlayback(any()) } answers {
            forwarded.add(firstArg())
            true
        }
    }

    @After fun cleanup() {
        unmockkAll()
        ReflectionHelpers.setStaticField(MediaController::class.java, "audioManager", null)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = false
    }

    @Test fun allPlaybackEntryPointsForwardWithoutTouchingLocalPlayerOrPauseFlags() {
        MediaController.sendPlayPause()
        MediaController.sendPreviousTrack()
        MediaController.sendNextTrack()
        MediaController.sendPause()
        MediaController.sendPause(force = true)
        MediaController.sendPlay()
        MediaController.sendPlay(replayWhenPaused = true, force = true)
        assertEquals(listOf(
            RelayPlaybackCommand(Action.PLAY_PAUSE), RelayPlaybackCommand(Action.PREVIOUS),
            RelayPlaybackCommand(Action.NEXT), RelayPlaybackCommand(Action.PAUSE),
            RelayPlaybackCommand(Action.PAUSE, force = true), RelayPlaybackCommand(Action.PLAY),
            RelayPlaybackCommand(Action.PLAY, force = true, replayWhenPaused = true),
        ), forwarded)
        verify { audio wasNot Called }
        assertFalse(MediaController.iPausedTheMedia)
        assertTrue(MediaController.userPlayedTheMedia)
    }

    @Test fun senderExecutionAndLocalHandoverBypassForwarding() {
        MediaController.sendPlayPause(routeToRelay = false)
        MediaController.sendPreviousTrack(routeToRelay = false)
        MediaController.sendNextTrack(routeToRelay = false)
        MediaController.sendPause(force = true, routeToRelay = false)
        MediaController.sendPlay(force = true, routeToRelay = false)
        assertTrue(forwarded.isEmpty())
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY).forEach { code ->
            verify(exactly = 1) { audio.dispatchMediaKeyEvent(match { it.keyCode == code && it.action == KeyEvent.ACTION_DOWN }) }
            verify(exactly = 1) { audio.dispatchMediaKeyEvent(match { it.keyCode == code && it.action == KeyEvent.ACTION_UP }) }
        }
    }

    @Test fun ordinaryPlaybackStillUsesLocalPlayerWithoutReceiverSession() {
        every { RelayPreviewService.forwardReceiverPlayback(any()) } returns false
        MediaController.sendNextTrack()
        verify(exactly = 2) { audio.dispatchMediaKeyEvent(match { it.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT }) }
    }
}
