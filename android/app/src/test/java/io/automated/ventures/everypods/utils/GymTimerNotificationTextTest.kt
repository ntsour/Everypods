package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GymTimerNotificationTextTest {
    @Test
    fun formatsShortAndLongDurationsForTheLockScreen() {
        assertEquals("00:09", GymTimerNotificationText.formatDuration(9_999L))
        assertEquals("01:05", GymTimerNotificationText.formatDuration(65_000L))
        assertEquals("1:01:05", GymTimerNotificationText.formatDuration(3_665_000L))
    }

    @Test
    fun describesHiitAndPausedStatesWithoutExposingControls() {
        assertEquals(
            "Work · Round 2 of 8",
            GymTimerNotificationText.runningText(GymTimer.Mode.HIIT, GymTimer.Phase.WORK, 2, 8)
        )
        assertEquals(
            "Countdown paused · 00:42",
            GymTimerNotificationText.pausedText(GymTimer.Mode.COUNTDOWN, 42_000L)
        )
    }
}
