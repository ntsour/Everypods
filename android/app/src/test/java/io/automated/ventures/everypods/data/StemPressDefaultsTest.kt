package io.automated.ventures.everypods.data

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType
import io.automated.ventures.everypods.utils.GymModeStemPressArbitration
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
    fun firstRunSeedingWritesListeningModeOnBothBuds() {
        // AirPodsService.onCreate seeds missing keys from allDefaults.
        val seed = StemPressDefaults.allDefaults
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, seed["left_long_press_action"])
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, seed["right_long_press_action"])
        assertEquals(StemAction.PLAY_PAUSE, seed["left_single_press_action"])
        assertEquals(StemAction.NEXT_TRACK, seed["left_double_press_action"])
        assertEquals(StemAction.NEXT_TRACK, seed["right_double_press_action"])
        assertEquals(StemAction.PREVIOUS_TRACK, seed["right_triple_press_action"])
    }

    /**
     * Gym Press Actions "Reset to defaults" removes gym_{left,right}_long_press_action,
     * so with Gym Mode on each bud falls back to its Controls long press. On default
     * Controls that is Listening Mode on both buds.
     */
    @Test
    fun gymResetFallsBackToControlsDefaultCycleOnBothBuds() {
        for (side in listOf("left", "right")) {
            val controls = StemAction.fromStringOrDefault(
                null, StemPressDefaults.defaultFor(side, StemPressType.LONG_PRESS).name
            )
            for (gymOn in listOf(true, false)) {
                assertEquals(
                    "$side gymOn=$gymOn",
                    StemAction.CYCLE_NOISE_CONTROL_MODES,
                    GymModeStemPressArbitration.resolveLongPressAction(gymOn, controls, null)
                )
            }
        }
    }

    @Test
    fun firmwareHandledLongPressIsListeningModeCycle() {
        // setupStemActions leaves long press to firmware only for this action.
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, StemPressDefaults.FIRMWARE_HANDLED_LONG_PRESS)
    }
}
