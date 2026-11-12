/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

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

    @Test fun leavingAllZonesRestoresImmediately() {
        val both = r(insideA(), Event.Arrived(1, "v2", "B"), snap(gymOn = true)).journal
        val leftA = r(both, Event.Exited(1, "A"), snap(gymOn = true)).journal
        val leftB = r(leftA, Event.Exited(1, "B"), snap(gymOn = true, hrActive = true))
        assertTrue(leftB.effects.containsAll(listOf(Effect.DisableGym, Effect.StopHr)))
        assertEquals(Presence.OUTSIDE, leftB.journal.presence)
    }

    @Test fun reEnteringAfterDepartureStartsAnotherVisit() {
        val leaving = r(insideA(), Event.Exited(1, "A"), snap(gymOn = true)).journal
        val back = r(leaving, Event.Arrived(1, "v9", "B"), snap(gymOn = true))
        assertFalse(back.effects.contains(Effect.CancelGraceCheck))
        assertNull(back.journal.departureDeadline)
        assertEquals("v9", back.journal.visitId)
    }

    @Test fun removingTheActiveZoneWhileInsideAnotherKeepsTheVisit() {
        val both = r(insideA(), Event.Arrived(1, "v2", "B"), snap(gymOn = true)).journal
        val removed = r(both, Event.RuleChanged, snap(zones = mapOf("B" to 1L), gymOn = true, revision = 2))
        assertTrue(removed.effects.isEmpty())
        assertEquals(setOf("B"), removed.journal.insideZones.keys)
        assertNull(removed.journal.departureDeadline)
    }

    @Test fun removingTheOnlyActiveZoneWaitsForFreshEvidence() {
        val removed = r(insideA(), Event.RuleChanged, snap(zones = mapOf("B" to 1L), gymOn = true, revision = 2))
        assertTrue(removed.effects.isEmpty())
        assertEquals(Presence.UNKNOWN, removed.journal.presence)
        assertFalse(removed.effects.contains(Effect.DisableGym))
        assertEquals("v1", removed.journal.visitId)
        assertTrue(removed.journal.ownsGym)
    }

    @Test fun movingTheActiveZoneDropsItUntilItsNewFenceConfirms() {
        val moved = r(insideA(), Event.RuleChanged, snap(zones = mapOf("A" to 2L, "B" to 1L), gymOn = true, revision = 2))
        assertTrue(moved.effects.isEmpty())
        assertEquals(Presence.UNKNOWN, moved.journal.presence)
        // Stale transitions from the old registration are ignored.
        assertEquals(moved.journal, r(moved.journal, Event.Exited(1, "A"), snap(zones = mapOf("A" to 2L, "B" to 1L))).journal)
        // The re-registered fence's initial DWELL keeps the same visit.
        val confirmed = r(moved.journal, Event.Arrived(2, "v5", "A"), snap(zones = mapOf("A" to 2L, "B" to 1L), gymOn = true, revision = 2))
        assertFalse(confirmed.effects.contains(Effect.CancelGraceCheck))
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
        val unknown = r(insideA(), Event.LocationUnavailable, snap(gymOn = true))
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
    @Test fun delayedExitCannotUndoNewerArrivalForTheSameZone() {
        val inside = r(Journal(), Event.Arrived(1, "current", "A", observedAt = 200)).journal
        val delayed = r(inside, Event.Exited(1, "A", observedAt = 100), snap(gymOn = true))
        assertEquals(inside, delayed.journal)
        assertTrue(delayed.effects.isEmpty())
    }

    @Test fun delayedArrivalCannotRestartVisitAfterNewerOutsideFix() {
        val outside = r(insideA(), Event.PresenceFix(1, Presence.OUTSIDE, "fix", observedAt = 200),
            snap(gymOn = true)).journal
        val delayed = r(outside, Event.Arrived(1, "old", "A", observedAt = 100))
        assertEquals(outside, delayed.journal)
        assertTrue(delayed.effects.isEmpty())
    }

    @Test fun insideFixDoesNotForgetPreviouslyTrackedZoneWhenItsBoundaryIsAmbiguous() {
        val fix = r(insideA(), Event.PresenceFix(1, Presence.INSIDE, "fix", mapOf("B" to 1L),
            observedAt = 200), snap(gymOn = true))
        val leftB = r(fix.journal, Event.Exited(1, "B", observedAt = 300), snap(gymOn = true))
        assertEquals(setOf("A"), leftB.journal.insideZones.keys)
        assertFalse(leftB.effects.contains(Effect.DisableGym))
        val leftA = r(leftB.journal, Event.Exited(1, "A", observedAt = 150), snap(gymOn = true))
        assertTrue(leftA.effects.contains(Effect.DisableGym))
    }

    @Test fun exitDuringUnknownRecoveryPreservesVisitUntilOutsideFix() {
        val unknown = r(insideA(), Event.ColdStart, snap(boot = "b2", gymOn = true)).journal
        val exit = r(unknown, Event.Exited(1, "A", observedAt = 200), snap(boot = "b2", gymOn = true))
        assertEquals("v1", exit.journal.visitId)
        assertTrue(exit.effects.isEmpty())
        val fix = r(exit.journal, Event.PresenceFix(1, Presence.OUTSIDE, "fix", observedAt = 300),
            snap(boot = "b2", gymOn = true))
        assertEquals(Presence.OUTSIDE, fix.journal.presence)
        assertTrue(fix.effects.contains(Effect.DisableGym))
    }
}
