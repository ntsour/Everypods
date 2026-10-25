package io.automated.ventures.everypods.automation

import io.automated.ventures.everypods.automation.GymLocationPermission.Action
import io.automated.ventures.everypods.automation.GymLocationPermission.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymLocationPermissionTest {
    @Test fun preciseFirst() {
        assertEquals(Step.NEED_PRECISE, GymLocationPermission.step(false, false))
        assertEquals(Step.NEED_PRECISE, GymLocationPermission.step(false, true))
    }
    @Test fun backgroundSecond() = assertEquals(Step.NEED_BACKGROUND, GymLocationPermission.step(true, false))
    @Test fun granted() = assertEquals(Step.GRANTED, GymLocationPermission.step(true, true))
    @Test fun firstTapAlwaysShowsDialog() {
        assertFalse(GymLocationPermission.shouldOpenSettingsForForeground(askedThisSession = false, canShowRationale = false))
        assertFalse(GymLocationPermission.shouldOpenSettingsForForeground(askedThisSession = true, canShowRationale = true))
        assertTrue(GymLocationPermission.shouldOpenSettingsForForeground(askedThisSession = true, canShowRationale = false))
    }
    @Test fun foregroundRequestsPreciseAndApproximate() {
        assertTrue(GymLocationPermission.FOREGROUND.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(GymLocationPermission.FOREGROUND.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun next(step: Step, fgAsked: Boolean = false, rationale: Boolean = false, bgAsked: Boolean = false) =
        GymLocationPermission.nextAction(step, fgAsked, rationale, bgAsked)

    @Test fun nothingGrantedExplainsForegroundFirst() = assertEquals(Action.EXPLAIN_FOREGROUND, next(Step.NEED_PRECISE))
    @Test fun foregroundDeniedOnceStillShowsDialog() =
        assertEquals(Action.EXPLAIN_FOREGROUND, next(Step.NEED_PRECISE, fgAsked = true, rationale = true))
    @Test fun foregroundPermanentlyDeniedOpensSettings() =
        assertEquals(Action.SETTINGS_FOREGROUND, next(Step.NEED_PRECISE, fgAsked = true, rationale = false))
    @Test fun whileUsingOnlyExplainsAllowAllTheTime() = assertEquals(Action.EXPLAIN_BACKGROUND, next(Step.NEED_BACKGROUND))
    @Test fun whileUsingAfterBackgroundAttemptOpensSettings() =
        assertEquals(Action.SETTINGS_BACKGROUND, next(Step.NEED_BACKGROUND, bgAsked = true))
    @Test fun grantedDoesNothing() = assertEquals(Action.NONE, next(Step.GRANTED, true, false, true))
    @Test fun foregroundGrantContinuesToBackgroundStep() {
        assertEquals(Action.EXPLAIN_BACKGROUND, GymLocationPermission.continueAfterForeground(true, false))
        assertEquals(Action.NONE, GymLocationPermission.continueAfterForeground(false, false))
        assertEquals(Action.NONE, GymLocationPermission.continueAfterForeground(true, true))
    }
    @Test fun pendingEnableCompletesOnlyWhenAllTheTimeGranted() {
        assertTrue(GymLocationPermission.shouldCompleteEnable(true, Step.GRANTED, hasLocation = true))
        assertFalse(GymLocationPermission.shouldCompleteEnable(true, Step.NEED_BACKGROUND, hasLocation = true))
        assertFalse(GymLocationPermission.shouldCompleteEnable(true, Step.GRANTED, hasLocation = false))
        assertFalse(GymLocationPermission.shouldCompleteEnable(false, Step.GRANTED, hasLocation = true))
    }
    @Test fun backgroundRequestedSeparately() =
        assertFalse(GymLocationPermission.FOREGROUND.contains(GymLocationPermission.BACKGROUND))
}
