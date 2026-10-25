/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateStartPlannerTest {
    private fun decide(
        active: Boolean = false, selected: HeartRateSharingMethod? = HeartRateSharingMethod.BLUETOOTH,
        dircon: Boolean = true, sdk: Int = 36, granted: Set<String>? = null,
    ) = HeartRateStartPlanner.decide(active, selected, dircon, sdk) { granted == null || it in granted }

    @Test fun runningHrIsLeftRunningWhateverTheSelection() {
        HeartRateSharingMethod.entries.forEach { assertEquals(HrStartDecision.AlreadyRunning, decide(active = true, selected = it)) }
        assertEquals(HrStartDecision.AlreadyRunning, decide(active = true, selected = null))
    }

    @Test fun noSelectionStartsNothingAndPicksNoMethod() {
        assertEquals(HrStartDecision.NoneSelected, decide(selected = null))
    }

    @Test fun explicitSelectionStartsExactlyThatMethod() {
        HeartRateSharingMethod.entries.forEach { assertEquals(HrStartDecision.Start(it), decide(selected = it)) }
    }

    @Test fun missingPermissionsAreReportedNotRequested() {
        val d = decide(granted = setOf("android.permission.POST_NOTIFICATIONS"))
        assertEquals(HrStartDecision.NeedsPermission(HeartRateSharingMethod.BLUETOOTH,
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE")), d)
    }

    @Test fun dirconFollowsBuildSupportWithoutFallingBack() {
        assertEquals(HrStartDecision.Unavailable(HeartRateSharingMethod.DIRCON),
            decide(selected = HeartRateSharingMethod.DIRCON, dircon = false))
    }

    @Test fun permissionSetMatchesTheCaseShortcut() {
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_CONNECT"),
            HeartRateStartPlanner.requiredPermissions(HeartRateSharingMethod.TICKR, 37))
        assertTrue(HeartRateStartPlanner.LOCAL_NETWORK in HeartRateStartPlanner.requiredPermissions(HeartRateSharingMethod.DIRCON, 37))
        assertFalse(HeartRateStartPlanner.LOCAL_NETWORK in HeartRateStartPlanner.requiredPermissions(HeartRateSharingMethod.DIRCON, 36))
    }

    @Test fun onlyDeliberateUserStopsAreOverrides() {
        assertTrue(HeartRateSource.CASE_SHORTCUT.isUserStop)
        assertTrue(HeartRateSource.HEART_RATE_SCREEN.isUserStop)
        assertTrue(HeartRateSource.NOTIFICATION.isUserStop)
        assertFalse(HeartRateSource.AUTOMATION.isUserStop)
        assertFalse(HeartRateSource.GYM_MODE.isUserStop)
        assertFalse(HeartRateSource.SYSTEM.isUserStop)
    }
}
