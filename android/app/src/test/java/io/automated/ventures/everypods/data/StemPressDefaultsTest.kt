package io.automated.ventures.everypods.data

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StemPressDefaultsTest {

    @Test
    fun longPressDefaultsToListeningModeOnBothBuds() {
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.LEFT_LONG)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.RIGHT_LONG)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.defaultFor("left", StemPressType.LONG_PRESS))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.defaultFor("right", StemPressType.LONG_PRESS))
    }

    /** Fresh install: the service fallback for every key equals what the Controls UI shows. */
    @Test
    fun freshInstallServiceDefaultsMatchControlsUi() {
        // What the Controls UI displays for an unset key (PressActionsScreen / CategoryScreen).
        val ui = mapOf(
            StemPressType.SINGLE_PRESS to StemAction.PLAY_PAUSE,
            StemPressType.DOUBLE_PRESS to StemAction.NEXT_TRACK,
            StemPressType.TRIPLE_PRESS to StemAction.PREVIOUS_TRACK,
            StemPressType.LONG_PRESS to StemAction.CYCLE_NOISE_CONTROL_MODES,
        )
        for (side in listOf("left", "right")) {
            for ((type, expected) in ui) {
                val key = StemPressDefaults.prefKey(side, type)
                // Service: stemActionFromPrefs(key, defaultForKey(key)) with key missing.
                val service = StemAction.fromStringOrDefault(null, StemPressDefaults.defaultForKey(key)!!.name)
                assertEquals("$key service", expected, service)
                assertEquals("$key ui", expected, StemPressDefaults.defaultFor(side, type))
            }
        }
    }

    @Test
    fun prefKeysMatchExistingPrefNames() {
        assertEquals("left_single_press_action", StemPressDefaults.prefKey("left", StemPressType.SINGLE_PRESS))
        assertEquals("right_double_press_action", StemPressDefaults.prefKey("RIGHT", StemPressType.DOUBLE_PRESS))
        assertEquals("left_triple_press_action", StemPressDefaults.prefKey("left", StemPressType.TRIPLE_PRESS))
        assertEquals("right_long_press_action", StemPressDefaults.prefKey("right", StemPressType.LONG_PRESS))
        assertEquals(8, StemPressDefaults.allDefaults.size)
        assertNull(StemPressDefaults.defaultForKey("gym_left_long_press_action"))
    }

    @Test
    fun resetToDefaultsGivesListeningModeOnBothBuds() {
        val reset = StemPressDefaults.resetValues()
        assertEquals("CYCLE_NOISE_CONTROL_MODES", reset["left_long_press_action"])
        assertEquals("CYCLE_NOISE_CONTROL_MODES", reset["right_long_press_action"])
        assertEquals("PLAY_PAUSE", reset["left_single_press_action"])
        assertEquals("NEXT_TRACK", reset["left_double_press_action"])
        assertEquals("NEXT_TRACK", reset["right_double_press_action"])
        assertEquals("PREVIOUS_TRACK", reset["right_triple_press_action"])
        assertEquals(8, reset.size)
    }

    @Test
    fun firmwareHandledLongPressIsListeningModeCycle() {
        // setupStemActions leaves long press to firmware only for this action.
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.FIRMWARE_HANDLED_LONG_PRESS)
    }
}
