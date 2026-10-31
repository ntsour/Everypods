package io.automated.ventures.everypods.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadGesturePrefsTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("gestures-test", Context.MODE_PRIVATE)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun `legacy combinations preserve enable and map requested toggle`() {
        for (enabled in listOf(false, true)) for (incoming in listOf(false, true)) for (mute in listOf(false, true)) {
            clear()
            prefs.edit().putBoolean("head_gestures_enabled", enabled).putBoolean("head_gestures_answer_call", incoming).putBoolean("head_gestures_mute_call", mute).commit()
            val s = HeadGesturePrefs.read(prefs)
            assertEquals(enabled, s.enabled)
            assertEquals(if (incoming) HeadGesture.NOD else null, s.calls[HeadGestureCallAction.ANSWER])
            assertEquals(if (incoming) HeadGesture.SHAKE else null, s.calls[HeadGestureCallAction.DECLINE])
            assertEquals(if (mute) HeadGesture.SHAKE else null, s.calls[HeadGestureCallAction.MUTE_TOGGLE])
            assertNull(s.calls[HeadGestureCallAction.HANG_UP])
            assertTrue(s.normal.values.all { it == null }); assertTrue(s.gym.values.all { it == null })
        }
    }
    @Test fun `fresh installation is off and has no call actions`() {
        val s = HeadGesturePrefs.read(prefs)
        assertFalse(s.enabled); assertTrue(s.calls.values.all { it == null })
    }
    @Test fun `migration is idempotent and ignores legacy changes afterwards`() {
        HeadGesturePrefs.read(prefs)
        HeadGesturePrefs.setAction(prefs, HeadGesture.NOD, false, StemAction.TOGGLE_GYM_MODE)
        prefs.edit().putBoolean("head_gestures_answer_call", true).commit()
        HeadGesturePrefs.migrate(prefs)
        assertEquals(StemAction.TOGGLE_GYM_MODE, HeadGesturePrefs.read(prefs).normal[HeadGesture.NOD])
        assertNull(HeadGesturePrefs.read(prefs).calls[HeadGestureCallAction.ANSWER])
    }
    @Test fun `call conflicts clear only same state`() {
        HeadGesturePrefs.setCall(prefs, HeadGestureCallAction.ANSWER, HeadGesture.NOD)
        HeadGesturePrefs.setCall(prefs, HeadGestureCallAction.MUTE_TOGGLE, HeadGesture.NOD)
        HeadGesturePrefs.setCall(prefs, HeadGestureCallAction.DECLINE, HeadGesture.NOD)
        val s = HeadGesturePrefs.read(prefs)
        assertNull(s.calls[HeadGestureCallAction.ANSWER])
        assertEquals(HeadGesture.NOD, s.calls[HeadGestureCallAction.DECLINE])
        assertEquals(HeadGesture.NOD, s.calls[HeadGestureCallAction.MUTE_TOGGLE])
    }
    @Test fun `unknown actions and out of range tuning are safe`() {
        HeadGesturePrefs.read(prefs)
        prefs.edit().putString(HeadGesturePrefs.actionKey(HeadGesture.NOD, false), "FUTURE_ACTION").commit()
        HeadGesturePrefs.setTuning(prefs, HeadGesture.NOD, false, GestureTuning(Int.MAX_VALUE, 99, -100))
        val s = HeadGesturePrefs.read(prefs)
        assertNull(s.normal[HeadGesture.NOD])
        assertEquals(GestureTuning(2000, 4, 500), s.tuningFor(HeadGesture.NOD, false))
    }
    @Test fun `gym tuning is copied once and retained across disable`() {
        HeadGesturePrefs.read(prefs)
        HeadGesturePrefs.setTuning(prefs, HeadGesture.NOD, false, GestureTuning(1200, 3, 3000))
        HeadGesturePrefs.setGymTuning(prefs, true)
        assertEquals(GestureTuning(1200, 3, 3000), HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, true))
        HeadGesturePrefs.setTuning(prefs, HeadGesture.NOD, true, GestureTuning(1600, 4, 4000))
        HeadGesturePrefs.setGymTuning(prefs, false)
        assertEquals(GestureTuning(1200, 3, 3000), HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, true))
        HeadGesturePrefs.setGymTuning(prefs, true)
        assertEquals(GestureTuning(1600, 4, 4000), HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, true))
    }
    @Test fun `shake announcement cancellation defaults on without enabling master and persists toggle`() {
        assertTrue(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
        assertFalse(HeadGesturePrefs.read(prefs).enabled)
        HeadGesturePrefs.setShakeStopsAnnouncements(prefs, false)
        HeadGesturePrefs.migrate(prefs)
        assertFalse(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
        HeadGesturePrefs.setShakeStopsAnnouncements(prefs, true)
        assertTrue(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
    }

    @Test fun `legacy manual tracking switch is retired without changing assignments`() {
        HeadGesturePrefs.read(prefs)
        HeadGesturePrefs.setAction(prefs, HeadGesture.NOD, false, StemAction.PLAY_PAUSE)
        prefs.edit().putInt("head_gesture_schema_version", 1).putBoolean("head_tracking_enabled", true).commit()
        val settings = HeadGesturePrefs.read(prefs)
        assertFalse(prefs.contains("head_tracking_enabled"))
        assertEquals(StemAction.PLAY_PAUSE, settings.normal[HeadGesture.NOD])
        assertFalse(settings.enabled)
    }

    @Test fun `upgrading main retains explicit spoken announcement preference`() {
        prefs.edit().putBoolean("head_gestures_stop_announcement", false).commit()
        assertFalse(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
        HeadGesturePrefs.setShakeStopsAnnouncements(prefs, true)
        prefs.edit().putInt("head_gesture_schema_version", 2).commit()
        assertTrue(HeadGesturePrefs.read(prefs).shakeStopsAnnouncements)
    }

    @Test fun `existing profiles retain original timing until configured`() {
        prefs.edit().putInt("head_gesture_schema_version", 3).commit()
        val settings = HeadGesturePrefs.read(prefs)
        for (gesture in HeadGesture.entries) for (gym in listOf(false, true)) {
            assertEquals(1500, settings.tuningFor(gesture, gym).maxPhaseGapMs)
            assertEquals(6000, settings.tuningFor(gesture, gym).maxDurationMs)
        }
    }

    @Test fun `speed tuning persists per gesture and profile and resets with tuning`() {
        HeadGesturePrefs.read(prefs)
        val fast = GestureTuning(maxPhaseGapMs = 300, maxDurationMs = 1250, axisDominancePercent = 250)
        HeadGesturePrefs.setTuning(prefs, HeadGesture.NOD, false, fast)
        HeadGesturePrefs.setGymTuning(prefs, true)
        assertEquals(fast, HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, true))
        val gymFast = fast.copy(maxPhaseGapMs = 200, maxDurationMs = 750, axisDominancePercent = 300)
        HeadGesturePrefs.setTuning(prefs, HeadGesture.NOD, true, gymFast)
        val settings = HeadGesturePrefs.read(prefs)
        assertEquals(fast, settings.tuningFor(HeadGesture.NOD, false))
        assertEquals(gymFast, settings.tuningFor(HeadGesture.NOD, true))
        assertEquals(GestureTuning(), settings.tuningFor(HeadGesture.SHAKE, true))
        HeadGesturePrefs.resetTuning(prefs, true)
        assertEquals(GestureTuning(), HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, true))
        assertEquals(fast, HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, false))
    }

    @Test fun `speed tuning clamps invalid values and rounds to selectable intervals`() {
        assertEquals(GestureTuning(maxPhaseGapMs = 200, maxDurationMs = 6000),
            GestureTuning(maxPhaseGapMs = Int.MIN_VALUE, maxDurationMs = Int.MAX_VALUE).validated())
        assertEquals(GestureTuning(maxPhaseGapMs = 1500, maxDurationMs = 500),
            GestureTuning(maxPhaseGapMs = Int.MAX_VALUE, maxDurationMs = Int.MIN_VALUE).validated())
        assertEquals(GestureTuning(maxPhaseGapMs = 400, maxDurationMs = 1250),
            GestureTuning(maxPhaseGapMs = 351, maxDurationMs = 1130).validated())
    }

    @Test fun `direction strictness is bounded and defaults to the existing ratio`() {
        assertEquals(150, HeadGesturePrefs.read(prefs).tuningFor(HeadGesture.NOD, false).axisDominancePercent)
        assertEquals(150, GestureTuning(axisDominancePercent = Int.MIN_VALUE).validated().axisDominancePercent)
        assertEquals(400, GestureTuning(axisDominancePercent = Int.MAX_VALUE).validated().axisDominancePercent)
        assertEquals(250, GestureTuning(axisDominancePercent = 226).validated().axisDominancePercent)
    }

}
