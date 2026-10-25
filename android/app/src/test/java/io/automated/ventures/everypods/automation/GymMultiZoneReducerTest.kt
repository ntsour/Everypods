/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import io.automated.ventures.everypods.automation.GymAutomationReducer.DEPARTURE_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GymMultiZoneReducerTest {
    private val ab = mapOf("A" to 1L, "B" to 1L)
    private fun snap(now: Long = 1_000, zones: Map<String, Long> = ab, gymOn: Boolean = false, hrActive: Boolean = false,
                     enabled: Boolean = true, boot: String = "b1", revision: Long = 1) =
        Snapshot(now, boot, enabled, revision, true, true, true, gymOn, hrActive, "BLUETOOTH",
            if (hrActive) HeartRateSource.AUTOMATION else null, zones)
    private fun r(j: Journal, e: Event, s: Snapshot = snap()) = GymAutomationReducer.reduce(j, e, s)

    private fun insideA(): Journal = r(Journal(ruleRevision = 1), Event.Arrived(1, "v1", "A")).journal

    @Test fun insideAThenBIsOneVisitAppliedOnce() {
        val a = insideA()
        val b = r(a, Event.Arrived(1, "v2", "B"), snap(gymOn = true, hrActive = true))
        assertTrue(b.effects.isEmpty())
        assertEquals("v1", b.journal.visitId)
        assertEquals(setOf("A", "B"), b.journal.insideZones.keys)
    }

    @Test fun leavingAWhileStillInBKeepsTheVisit() {
        val both = r(insideA(), Event.Arrived(1, "v2", "B"), snap(gymOn = true)).journal
        val leftA = r(both, Event.Exited(1, "A"), snap(gymOn = true))
        assertTrue(leftA.effects.isEmpty())
        assertNull(leftA.journal.departureDeadline)
        assertEquals(setOf("B"), leftA.journal.insideZones.keys)
        assertEquals(Presence.INSIDE, leftA.journal.presence)
    }

    @Test fun leavingAllZonesStartsTheGraceAndThenRestores() {
        val both = r(insideA(), Event.Arrived(1, "v2", "B"), snap(gymOn = true)).journal
        val leftA = r(both, Event.Exited(1, "A"), snap(gymOn = true)).journal
        val leftB = r(leftA, Event.Exited(1, "B"), snap(gymOn = true))
        assertEquals(listOf(Effect.ScheduleGraceCheck(1_000 + DEPARTURE_GRACE_MS)), leftB.effects)
        val early = r(leftB.journal, Event.GraceCheck, snap(now = 2_000, gymOn = true))
        assertFalse(early.effects.contains(Effect.DisableGym))
        val done = r(leftB.journal, Event.GraceCheck, snap(now = 1_000 + DEPARTURE_GRACE_MS, gymOn = true, hrActive = true))
        assertTrue(done.effects.containsAll(listOf(Effect.DisableGym, Effect.StopHr)))
        assertEquals(Presence.OUTSIDE, done.journal.presence)
    }

    @Test fun reEnteringAnyZoneDuringGraceCancelsIt() {
        val leaving = r(insideA(), Event.Exited(1, "A"), snap(gymOn = true)).journal
        val back = r(leaving, Event.Arrived(1, "v9", "B"), snap(gymOn = true))
        assertTrue(back.effects.contains(Effect.CancelGraceCheck))
        assertNull(back.journal.departureDeadline)
        assertEquals("v1", back.journal.visitId)
    }

    @Test fun removingTheActiveZoneWhileInsideAnotherKeepsTheVisit() {
        val both = r(insideA(), Event.Arrived(1, "v2", "B"), snap(gymOn = true)).journal
        val removed = r(both, Event.RuleChanged, snap(zones = mapOf("B" to 1L), gymOn = true, revision = 2))
        assertTrue(removed.effects.isEmpty())
        assertEquals(setOf("B"), removed.journal.insideZones.keys)
        assertNull(removed.journal.departureDeadline)
    }

    @Test fun removingTheOnlyActiveZoneUsesTheGraceNeverAnInstantRestore() {
        val removed = r(insideA(), Event.RuleChanged, snap(zones = mapOf("B" to 1L), gymOn = true, revision = 2))
        assertEquals(listOf(Effect.ScheduleGraceCheck(1_000 + DEPARTURE_GRACE_MS)), removed.effects)
        assertFalse(removed.effects.contains(Effect.DisableGym))
        assertEquals("v1", removed.journal.visitId)
        assertTrue(removed.journal.ownsGym)
    }

    @Test fun movingTheActiveZoneDropsItUntilItsNewFenceConfirms() {
        val moved = r(insideA(), Event.RuleChanged, snap(zones = mapOf("A" to 2L, "B" to 1L), gymOn = true, revision = 2))
        assertTrue(moved.effects.first() is Effect.ScheduleGraceCheck)
        // Stale transitions from the old registration are ignored.
        assertEquals(moved.journal, r(moved.journal, Event.Exited(1, "A"), snap(zones = mapOf("A" to 2L, "B" to 1L))).journal)
        // The re-registered fence's initial DWELL keeps the same visit.
        val confirmed = r(moved.journal, Event.Arrived(2, "v5", "A"), snap(zones = mapOf("A" to 2L, "B" to 1L), gymOn = true, revision = 2))
        assertTrue(confirmed.effects.contains(Effect.CancelGraceCheck))
        assertEquals("v1", confirmed.journal.visitId)
        assertFalse(confirmed.effects.contains(Effect.EnableGym))
    }

    @Test fun addingAZoneDuringAVisitDoesNotResetIt() {
        val a = insideA()
        val added = r(a, Event.RuleChanged, snap(zones = mapOf("A" to 1L, "B" to 1L, "C" to 1L), gymOn = true, revision = 2))
        assertTrue(added.effects.isEmpty())
        assertEquals(a.copy(ruleRevision = 2), added.journal)
    }

    @Test fun manualGymOffSuppressesAcrossZonesForTheVisit() {
        val off = r(insideA(), Event.ManualGymOff).journal
        val b = r(off, Event.Arrived(1, "v2", "B"))
        assertTrue(b.effects.isEmpty())
        assertTrue(b.journal.gymSuppressed)
    }

    @Test fun disablingTheRuleEndsTheVisit() {
        val off = r(insideA(), Event.RuleChanged, snap(enabled = false, gymOn = true))
        assertTrue(off.effects.contains(Effect.DisableGym))
        assertNull(off.journal.visitId)
    }

    @Test fun unknownZoneOrStaleRevisionIsIgnored() {
        val j = Journal(ruleRevision = 1)
        assertEquals(j, r(j, Event.Arrived(1, "v", "Z")).journal)
        assertEquals(j, r(j, Event.Arrived(9, "v", "A")).journal)
    }

    @Test fun unavailableSignalNeverCountsAsLeftAcrossZones() {
        val leaving = r(insideA(), Event.Exited(1, "A"), snap(gymOn = true)).journal
        val unknown = r(leaving, Event.LocationUnavailable, snap(gymOn = true))
        assertNull(unknown.journal.departureDeadline)
        assertEquals("v1", unknown.journal.visitId)
    }

    @Test fun rebootClearsZoneEvidenceButKeepsSuppression() {
        val off = r(insideA(), Event.ManualGymOff).journal
        val cold = r(off, Event.ColdStart, snap(boot = "b2"))
        assertTrue(cold.journal.insideZones.isEmpty())
        assertEquals(Presence.UNKNOWN, cold.journal.presence)
        assertTrue(cold.journal.gymSuppressed)
        val fix = r(cold.journal, Event.PresenceFix(1, Presence.INSIDE, "f", mapOf("B" to 1L)), snap(boot = "b2"))
        assertEquals(setOf("B"), fix.journal.insideZones.keys)
        assertTrue(fix.effects.isEmpty())
    }
}
