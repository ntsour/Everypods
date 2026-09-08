package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymTimerAnnouncementTextTest {

    @Test
    fun `countdown milestone and final-second cue are combined`() {
        val cue = GymTimerAnnouncementText.forEvent(
            GymTimer.AnnouncementEvent.CountdownCue(
                GymTimer.CountdownMilestone.HALF,
                10
            ),
            stopwatchIntervalMinutes = 5
        )

        assertEquals("Halfway. 10", cue)
    }

    @Test
    fun `stopwatch interval respects selected cadence`() {
        val event = GymTimer.AnnouncementEvent.StopwatchInterval(5 * 60_000L)

        assertEquals("5 minutes elapsed.", GymTimerAnnouncementText.forEvent(event, 5))
        assertNull(GymTimerAnnouncementText.forEvent(event, 10))
        assertNull(GymTimerAnnouncementText.forEvent(event, 0))
    }

    @Test
    fun `hiit final countdown only speaks ten and final three seconds`() {
        assertEquals(
            "10",
            GymTimerAnnouncementText.forEvent(GymTimer.AnnouncementEvent.HiitCountdown(10), 5)
        )
        assertEquals(
            "3",
            GymTimerAnnouncementText.forEvent(GymTimer.AnnouncementEvent.HiitCountdown(3), 5)
        )
    }

    @Test
    fun `completion text identifies the timer mode`() {
        assertEquals(
            "Countdown complete.",
            GymTimerAnnouncementText.forEvent(
                GymTimer.AnnouncementEvent.Completed(GymTimer.Mode.COUNTDOWN),
                5
            )
        )
        assertEquals(
            "HIIT complete.",
            GymTimerAnnouncementText.forEvent(
                GymTimer.AnnouncementEvent.Completed(GymTimer.Mode.HIIT),
                5
            )
        )
    }
}
