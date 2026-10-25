/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymRadiusTest {
    @Test fun snapsToTwentyMetreGridWithinRange() {
        mapOf(5 to 20, 20 to 20, 29 to 20, 30 to 40, 129 to 120, 139 to 140, 150 to 160, 160 to 160,
            250 to 260, 299 to 300, 300 to 300, 500 to 300, 1000 to 300, -10 to 20).forEach { (input, expected) ->
            assertEquals("radius $input", expected, GymAutomationRule.normalizeRadius(input))
        }
        assertEquals(160, GymAutomationRule.normalizeRadius(Float.NaN))
        assertEquals(180, GymAutomationRule.normalizeRadius(171.4f))
    }

    @Test fun defaultAndSliderStepsAreOnTheGrid() {
        assertEquals(GymAutomationRule.DEFAULT_RADIUS_M, GymAutomationRule.normalizeRadius(GymAutomationRule.DEFAULT_RADIUS_M))
        assertEquals(13, GymAutomationRule.RADIUS_SLIDER_STEPS) // 20, 40, … 300 = 15 stops
        val stops = (GymAutomationRule.MIN_RADIUS_M..GymAutomationRule.MAX_RADIUS_M step GymAutomationRule.RADIUS_STEP_M).toList()
        assertEquals(15, stops.size)
        assertTrue(stops.all { GymAutomationRule.normalizeRadius(it) == it })
    }

    @Test fun presenceCombinesAcrossZones() {
        assertEquals(Presence.INSIDE to mapOf("b" to 2L), PresenceEvidence.combine(listOf(
            Triple("a", 1L, Presence.OUTSIDE), Triple("b", 2L, Presence.INSIDE))))
        assertEquals(Presence.UNKNOWN to emptyMap<String, Long>(), PresenceEvidence.combine(listOf(
            Triple("a", 1L, Presence.OUTSIDE), Triple("b", 2L, Presence.UNKNOWN))))
        assertEquals(Presence.OUTSIDE to emptyMap<String, Long>(), PresenceEvidence.combine(listOf(
            Triple("a", 1L, Presence.OUTSIDE), Triple("b", 2L, Presence.OUTSIDE))))
        assertEquals(Presence.UNKNOWN to emptyMap<String, Long>(), PresenceEvidence.combine(emptyList()))
    }

    @Test fun mapZoomHandlesTheWholeRadiusRange() {
        val small = GymMapMath.zoomForRadius(20, 40.4, 1000, 2.6f)
        val large = GymMapMath.zoomForRadius(300, 40.4, 1000, 2.6f)
        assertTrue(small <= GymMapMath.MAX_ZOOM && small >= GymMapMath.MIN_ZOOM)
        assertTrue(large < small)
        assertTrue(large >= GymMapMath.MIN_ZOOM)
    }
}
