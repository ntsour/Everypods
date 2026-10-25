package io.automated.ventures.everypods.automation

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
}
