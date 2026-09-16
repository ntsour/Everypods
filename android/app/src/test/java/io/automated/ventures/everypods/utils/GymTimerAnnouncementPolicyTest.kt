package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymTimerAnnouncementPolicyTest {
    @Test
    fun keepsStartAndCompletionSpeechRegardlessOfOptionalCueSettings() {
        val started = GymTimer.AnnouncementEvent.Started(GymTimer.Mode.COUNTDOWN)
        val completed = GymTimer.AnnouncementEvent.Completed(GymTimer.Mode.COUNTDOWN)
        assertEquals(started, GymTimerAnnouncementPolicy.eventForSpeech(started, false, false))
        assertEquals(completed, GymTimerAnnouncementPolicy.eventForSpeech(completed, false, false))
    }

    @Test
    fun filtersIntermediateAndFinalCountdownCuesIndependently() {
        val cue = GymTimer.AnnouncementEvent.CountdownCue(
            GymTimer.CountdownMilestone.HALF,
            10
        )
        assertEquals(
            GymTimer.AnnouncementEvent.CountdownCue(null, 10),
            GymTimerAnnouncementPolicy.eventForSpeech(cue, false, true)
        )
        assertEquals(
            GymTimer.AnnouncementEvent.CountdownCue(GymTimer.CountdownMilestone.HALF, null),
            GymTimerAnnouncementPolicy.eventForSpeech(cue, true, false)
        )
        assertNull(GymTimerAnnouncementPolicy.eventForSpeech(cue, false, false))
    }
}
