package io.automated.ventures.everypods.relay

import org.junit.Assert.*
import org.junit.Test

class RelayPlaybackToggleTest {
    @Test fun pauseAndResumeUseTheSendersState() {
        val toggle = RelayPlaybackToggle()
        val modes = mutableListOf<Boolean>()
        val pause = toggle.request("sender", modes::add)!!
        assertTrue(toggle.complete("sender", RelayPlaybackToggle.result(pause, true)!!))
        val resume = toggle.request("sender", modes::add)!!
        assertTrue(toggle.complete("sender", RelayPlaybackToggle.result(resume, false)!!))
        assertEquals(listOf(true, false), modes)
    }

    @Test fun ignoresOtherPeersStaleResponsesAndDuplicates() {
        val toggle = RelayPlaybackToggle()
        var calls = 0
        val oldRequest = toggle.request("sender") { calls++ }!!
        toggle.clear()
        val current = toggle.request("sender") { calls++ }!!
        assertNull(toggle.request("sender") { calls++ })
        assertFalse(toggle.complete("other", RelayPlaybackToggle.result(current, true)!!))
        assertFalse(toggle.complete("sender", RelayPlaybackToggle.result(oldRequest, true)!!))
        assertTrue(toggle.complete("SENDER", RelayPlaybackToggle.result(current, true)!!))
        assertFalse(toggle.complete("sender", RelayPlaybackToggle.result(current, true)!!))
        assertEquals(1, calls)
    }

    @Test fun rejectsMalformedPacketsWithoutCompletingAction() {
        val toggle = RelayPlaybackToggle()
        var called = false
        val request = toggle.request("sender") { called = true }!!
        assertNull(RelayPlaybackToggle.result(byteArrayOf(RelaySessionControl.PLAYBACK_TOGGLE), true))
        assertNull(RelayPlaybackToggle.result(request.copyOf().apply { this[0] = 99 }, true))
        val result = RelayPlaybackToggle.result(request, true)!!
        assertFalse(toggle.complete("sender", result.copyOf(9)))
        assertFalse(toggle.complete("sender", result.copyOf().apply { this[9] = 2 }))
        assertFalse(toggle.complete("sender", result.copyOf().apply { this[0] = 99 }))
        assertFalse(called)
        assertTrue(toggle.complete("sender", result))
        assertTrue(called)
    }

    @Test fun controlsOnlyActiveReceiverSessionsIncludingPausedSource() {
        RelayRole.entries.forEach { role ->
            RelayStage.entries.forEach { stage ->
                val expected = role == RelayRole.RECEIVER && stage in setOf(
                    RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED,
                )
                assertEquals("$role / $stage", expected,
                    RelayPlaybackToggle.receivesPlaybackControls(RelayUiState(role = role, stage = stage)))
            }
        }
    }
}
