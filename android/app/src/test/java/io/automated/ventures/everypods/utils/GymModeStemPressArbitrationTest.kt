package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressBudType
import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType
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
}
