package io.automated.ventures.everypods.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymTimerAnnouncementTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun formatEvent(
        event: GymTimer.AnnouncementEvent,
        stopwatchIntervalMinutes: Int
    ): String? = GymTimerAnnouncementText.forEvent(context, "en", event, stopwatchIntervalMinutes)?.text

    @Test
    fun preparationCountdownAnnouncesEachNumberWithoutExtraSpeech() {
        assertEquals(
            "5",
            formatEvent(GymTimer.AnnouncementEvent.PreparationCountdown(5), 5)
        )
        assertEquals(
            "4",
            formatEvent(GymTimer.AnnouncementEvent.PreparationCountdown(4), 5)
        )
    }

    @Test
    fun `countdown milestone and final-second cue are combined`() {
        val cue = formatEvent(
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

        assertEquals("5 minutes elapsed.", formatEvent(event, 5))
        assertNull(formatEvent(event, 10))
        assertNull(formatEvent(event, 0))
    }

    @Test
    fun `hiit final countdown speaks the final five seconds`() {
        assertEquals(
            "5",
            formatEvent(GymTimer.AnnouncementEvent.HiitCountdown(5), 5)
        )
        assertEquals(
            "3",
            formatEvent(GymTimer.AnnouncementEvent.HiitCountdown(3), 5)
        )
    }

    @Test
    fun `completion text identifies the timer mode`() {
        assertEquals(
            "Countdown complete.",
            formatEvent(
                GymTimer.AnnouncementEvent.Completed(GymTimer.Mode.COUNTDOWN),
                5
            )
        )
        assertEquals(
            "HIIT complete.",
            formatEvent(
                GymTimer.AnnouncementEvent.Completed(GymTimer.Mode.HIIT),
                5
            )
        )
    }
}
