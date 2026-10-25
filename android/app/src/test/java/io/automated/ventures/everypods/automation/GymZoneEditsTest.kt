/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymZoneEditsTest {
    private val range = GymAutomationRule.MIN_RADIUS_M.toFloat()..GymAutomationRule.MAX_RADIUS_M.toFloat()

    /** Mirrors StyledSlider: each drag delta moves relative to the value it was given. */
    private fun drag(state: GymRadiusSliderState, totalPx: Float, trackPx: Float = 900f, steps: Int = 60) {
        repeat(steps) {
            val fraction = (state.raw - range.start) / (range.endInclusive - range.start)
            val target = range.start + (fraction + totalPx / steps / trackPx) * (range.endInclusive - range.start)
            state.onValueChange(target.coerceIn(range.start, range.endInclusive))
        }
    }

    @Test fun smallDragDeltasMoveTheRadiusInTwentyMetreSteps() {
        val s = GymRadiusSliderState(100)
        drag(s, totalPx = 900f / 14f * 3f) // three stops right, in 60 tiny deltas
        assertEquals(160, s.meters)
        assertEquals(160, s.pendingWrite())
        drag(s, totalPx = -2000f)
        assertEquals(20, s.meters)
        drag(s, totalPx = 5000f)
        assertEquals(300, s.meters)
        assertTrue((20..300 step 20).contains(s.meters))
    }

    @Test fun oldBindingSnappedEveryDeltaAndNeverMoved() {
        // Regression guard for the bug: snapping the bound value on every delta.
        var value = 100f
        repeat(60) {
            val fraction = (value - range.start) / (range.endInclusive - range.start)
            val target = range.start + (fraction + 3f / 900f) * (range.endInclusive - range.start)
            value = GymAutomationRule.normalizeRadius(target).toFloat()
        }
        assertEquals(100f, value) // the broken path; the state holder above must not behave like this
    }

    @Test fun ownEchoIsIgnoredExternalChangeIsApplied() {
        val s = GymRadiusSliderState(100)
        drag(s, totalPx = 900f / 14f)
        val w = s.pendingWrite()!!
        s.markPersisted(w)
        assertFalse(s.onStored(w))
        assertNull(s.pendingWrite())
        assertTrue(s.onStored(240))
        assertEquals(240, s.meters)
        assertNull(s.pendingWrite())
    }

    private val real = GymZone(id = "real", name = "Gym 1", latitude = 40.481547, longitude = -3.753771, radiusMeters = 260)
    private val other = GymZone(id = "b", name = "B", latitude = 41.0, longitude = 2.0, radiusMeters = 100)
    private val rule = GymAutomationRule(zones = listOf(real, other))

    @Test fun radiusWriteUsesLatestStoredZoneAndChangesOnlyRadius() {
        // The pin moved after the radius debounce started; the radius write must keep the new pin.
        val moved = GymZoneEdits.withPosition(rule, "b", 41.5, 2.5)
        val after = GymZoneEdits.withRadius(moved, "b", 180)
        assertEquals(GymZone(id = "b", name = "B", latitude = 41.5, longitude = 2.5, radiusMeters = 180), after.zone("b"))
        assertEquals(real, after.zone("real"))
        val renamed = GymZoneEdits.withName(after, "b", "New")
        assertEquals(180, renamed.zone("b")!!.radiusMeters)
        assertEquals(41.5, renamed.zone("b")!!.latitude!!, 0.0)
    }

    @Test fun openingTheEditorWritesNothing() {
        assertFalse(GymZoneEdits.shouldPersistCenter(false, real, 40.474, -3.706))
        assertFalse(GymZoneEdits.shouldPersistCenter(true, real, real.latitude!!, real.longitude!!))
        assertTrue(GymZoneEdits.shouldPersistCenter(true, real, 40.49, -3.75))
        assertTrue(GymZoneEdits.shouldPersistCenter(true, GymZone(id = "n"), 40.4, -3.7))
        assertFalse(GymZoneEdits.shouldPersistCenter(true, null, 40.4, -3.7))
    }

    @Test fun positionIsClampedToMapLatitude() {
        val r = GymZoneEdits.withPosition(rule, "b", -89.0, 2.0)
        assertEquals(-GymMapMath.MAX_LAT, r.zone("b")!!.latitude!!, 1e-5)
    }

    @Test fun newZoneStartsNearCurrentThenExistingZoneThenMadrid() {
        assertEquals(1.0 to 2.0, GymZoneEdits.startPosition(1.0 to 2.0, rule))
        assertEquals(real.latitude!! to real.longitude!!, GymZoneEdits.startPosition(null, rule))
        assertEquals(GymZoneEdits.FALLBACK_LAT to GymZoneEdits.FALLBACK_LON, GymZoneEdits.startPosition(null, GymAutomationRule()))
    }
}
