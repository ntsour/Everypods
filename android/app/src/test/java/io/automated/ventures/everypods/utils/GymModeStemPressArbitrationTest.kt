package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressBudType
import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType
import io.automated.ventures.everypods.data.StemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymModeStemPressArbitrationTest {
    @Test
    fun defersOnlySinglePressesWhileGymModeIsEnabled() {
        assertTrue(GymModeStemPressArbitration.shouldDeferSinglePress(true, StemPressType.SINGLE_PRESS))
        assertFalse(GymModeStemPressArbitration.shouldDeferSinglePress(false, StemPressType.SINGLE_PRESS))
        assertFalse(GymModeStemPressArbitration.shouldDeferSinglePress(true, StemPressType.DOUBLE_PRESS))
    }

    @Test
    fun laterMultiPressOnlyCancelsTheSinglePressFromTheSameBud() {
        assertTrue(
            GymModeStemPressArbitration.supersedesDeferredSinglePress(
                StemPressBudType.LEFT,
                StemPressBudType.LEFT,
                StemPressType.DOUBLE_PRESS
            )
        )
        assertFalse(
            GymModeStemPressArbitration.supersedesDeferredSinglePress(
                StemPressBudType.LEFT,
                StemPressBudType.RIGHT,
                StemPressType.DOUBLE_PRESS
            )
        )
    }

    @Test
    fun suppressesSinglePressThatArrivesAfterAGymModeMultiPress() {
        assertTrue(
            GymModeStemPressArbitration.suppressesSinglePressAfterMultiPress(
                gymModeEnabled = true,
                lastMultiPressAtMs = 1_000L,
                nowMs = 1_200L,
            )
        )
        assertFalse(
            GymModeStemPressArbitration.suppressesSinglePressAfterMultiPress(
                gymModeEnabled = true,
                lastMultiPressAtMs = 1_000L,
                nowMs = 1_251L,
            )
        )
    }

    @Test
    fun longPressKeepsToggleGymModeEvenWhenGymModeIsOn() {
        assertEquals(
            StemAction.TOGGLE_GYM_MODE,
            GymModeStemPressArbitration.resolveLongPressAction(
                gymModeEnabled = true,
                normalLongPress = StemAction.TOGGLE_GYM_MODE,
                gymLongPress = StemAction.GYM_TIMER_RESET,
            )
        )
    }

    @Test
    fun longPressUsesGymOverlayWhenNormalIsNotToggle() {
        assertEquals(
            StemAction.GYM_TIMER_LAP,
            GymModeStemPressArbitration.resolveLongPressAction(
                gymModeEnabled = true,
                normalLongPress = StemAction.CYCLE_NOISE_CONTROL_MODES,
                gymLongPress = StemAction.GYM_TIMER_LAP,
            )
        )
    }

    @Test
    fun longPressUsesNormalWhenGymModeOff() {
        assertEquals(
            StemAction.TOGGLE_GYM_MODE,
            GymModeStemPressArbitration.resolveLongPressAction(
                gymModeEnabled = false,
                normalLongPress = StemAction.TOGGLE_GYM_MODE,
                gymLongPress = StemAction.GYM_TIMER_RESET,
            )
        )
    }

    @Test
    fun leftControlsToggleDoesNotForceRightBudToToggle() {
        // Simulate right bud while left Controls is TOGGLE: right uses its own maps.
        assertEquals(
            StemAction.GYM_TIMER_LAP,
            GymModeStemPressArbitration.resolveLongPressAction(
                gymModeEnabled = true,
                normalLongPress = StemAction.CYCLE_NOISE_CONTROL_MODES,
                gymLongPress = StemAction.GYM_TIMER_LAP,
            )
        )
    }

    @Test
    fun gymOverlayToggleIsNeverHonoredWhileGymOn() {
        assertEquals(
            StemAction.GYM_TIMER_RESET,
            GymModeStemPressArbitration.resolveLongPressAction(
                gymModeEnabled = true,
                normalLongPress = StemAction.CYCLE_NOISE_CONTROL_MODES,
                gymLongPress = StemAction.TOGGLE_GYM_MODE,
            )
        )
    }

    @Test
    fun locksGymLongPressOnlyWhenControlsOwnsToggle() {
        assertTrue(
            GymModeStemPressArbitration.isGymLongPressLockedByControls(StemAction.TOGGLE_GYM_MODE)
        )
        assertFalse(
            GymModeStemPressArbitration.isGymLongPressLockedByControls(
                StemAction.CYCLE_NOISE_CONTROL_MODES
            )
        )
    }

    // ── Unset gym long-press falls back to Controls (left LP "does nothing" fix) ──

    private fun resolve(gymOn: Boolean, normal: StemAction, gym: StemAction?) =
        GymModeStemPressArbitration.resolveLongPressAction(
            gymModeEnabled = gymOn,
            normalLongPress = normal,
            gymLongPress = gym,
        )

    @Test
    fun gymOffUsesNormalRegardlessOfGymMap() {
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES,
            resolve(false, StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.GYM_TIMER_RESET))
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES,
            resolve(false, StemAction.CYCLE_NOISE_CONTROL_MODES, null))
    }

    @Test
    fun gymOnWithUnsetGymLongPressUsesControlsAction() {
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES,
            resolve(true, StemAction.CYCLE_NOISE_CONTROL_MODES, null))
        assertEquals(StemAction.DIGITAL_ASSISTANT,
            resolve(true, StemAction.DIGITAL_ASSISTANT, null))
    }

    @Test
    fun gymOnWithExplicitTimerResetIsRespected() {
        assertEquals(StemAction.GYM_TIMER_RESET,
            resolve(true, StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.GYM_TIMER_RESET))
    }

    @Test
    fun gymOnWithExplicitCycleUsesCycle() {
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES,
            resolve(true, StemAction.DIGITAL_ASSISTANT, StemAction.CYCLE_NOISE_CONTROL_MODES))
    }

    @Test
    fun controlsToggleGymModeWinsWithGymOnEvenWhenGymUnsetOrExplicit() {
        assertEquals(StemAction.TOGGLE_GYM_MODE, resolve(true, StemAction.TOGGLE_GYM_MODE, null))
        assertEquals(StemAction.TOGGLE_GYM_MODE,
            resolve(true, StemAction.TOGGLE_GYM_MODE, StemAction.CYCLE_NOISE_CONTROL_MODES))
    }

    @Test
    fun gymMapToggleGymModeFallsBackToTimerReset() {
        assertEquals(StemAction.GYM_TIMER_RESET,
            resolve(true, StemAction.PLAY_PAUSE, StemAction.TOGGLE_GYM_MODE))
    }

    @Test
    fun budsResolveIndependently() {
        // Device repro: left Controls CYCLE + gym unset, right Controls X + gym CYCLE.
        val left = resolve(true, StemAction.CYCLE_NOISE_CONTROL_MODES, null)
        val right = resolve(true, StemAction.DIGITAL_ASSISTANT, StemAction.CYCLE_NOISE_CONTROL_MODES)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, left)
        assertEquals(StemAction.CYCLE_NOISE_CONTROL_MODES, right)
        // Left explicit reset does not leak into right's unset fallback.
        assertEquals(StemAction.GYM_TIMER_RESET,
            resolve(true, StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.GYM_TIMER_RESET))
        assertEquals(StemAction.DIGITAL_ASSISTANT, resolve(true, StemAction.DIGITAL_ASSISTANT, null))
    }

    @Test
    fun overrideHintOnlyWhenGymOnAndExplicitAndNotControlsToggle() {
        assertFalse(GymModeStemPressArbitration.gymLongPressOverridesControls(
            false, StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.GYM_TIMER_RESET))
        assertFalse(GymModeStemPressArbitration.gymLongPressOverridesControls(
            true, StemAction.CYCLE_NOISE_CONTROL_MODES, null))
        assertFalse(GymModeStemPressArbitration.gymLongPressOverridesControls(
            true, StemAction.TOGGLE_GYM_MODE, StemAction.GYM_TIMER_RESET))
        assertTrue(GymModeStemPressArbitration.gymLongPressOverridesControls(
            true, StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.GYM_TIMER_RESET))
    }
}
