/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import io.automated.ventures.everypods.automation.GymAutomationReducer.DEPARTURE_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymAutomationReducerTest {
    private fun snap(
        now: Long = 1_000, boot: String = "b1", ready: Boolean = true, gymOn: Boolean = false,
        hrActive: Boolean = false, method: String? = "BLUETOOTH", hrAction: Boolean = true,
        restore: Boolean = true, enabled: Boolean = true, revision: Long = 1,
    ) = Snapshot(now, boot, enabled, revision, hrAction, restore, ready, gymOn, hrActive, method)

    private fun arrive(j: Journal = Journal(ruleRevision = 1), s: Snapshot = snap()) =
        GymAutomationReducer.reduce(j, Event.Arrived(1, "v1"), s)

    @Test fun arrivalWithAirPodsEnablesGymAndStartsSelectedHrOnce() {
        val r = arrive()
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), r.effects)
        assertTrue(r.journal.ownsGym && r.journal.ownsHr)
        val again = GymAutomationReducer.reduce(r.journal, Event.Arrived(1, "v2"), snap(gymOn = true, hrActive = true))
        assertTrue(again.effects.isEmpty())
        val rec = GymAutomationReducer.reduce(r.journal, Event.Reconcile, snap(gymOn = true, hrActive = true))
        assertTrue(rec.effects.isEmpty())
    }

    @Test fun noSelectedMethodNeverStartsHr() {
        val r = arrive(s = snap(method = null))
        assertEquals(listOf(Effect.EnableGym), r.effects)
        assertFalse(r.journal.ownsHr)
    }

    @Test fun hrActionDisabledNeverStartsHr() {
        assertEquals(listOf(Effect.EnableGym), arrive(s = snap(hrAction = false)).effects)
    }

    @Test fun runningHrIsLeftAloneAndNotOwned() {
        val r = arrive(s = snap(hrActive = true))
        assertEquals(listOf(Effect.EnableGym), r.effects)
        assertFalse(r.journal.ownsHr)
        val left = depart(r.journal, snap(gymOn = true, hrActive = true))
        assertFalse(left.effects.contains(Effect.StopHr))
    }

    @Test fun gymAlreadyOnIsNotOwnedNorRestored() {
        val r = arrive(s = snap(gymOn = true))
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")), r.effects)
        val left = depart(r.journal, snap(gymOn = true, hrActive = true))
        assertEquals(listOf(Effect.StopHr), left.effects)
    }

    @Test fun airPodsConnectingLaterActivatesThen() {
        val r = arrive(s = snap(ready = false))
        assertTrue(r.effects.isEmpty())
        assertEquals(Presence.INSIDE, r.journal.presence)
        val later = GymAutomationReducer.reduce(r.journal, Event.Reconcile, snap(ready = true))
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), later.effects)
    }

    private fun depart(j: Journal, s: Snapshot): Result {
        val exit = GymAutomationReducer.reduce(j, Event.Exited(1), s)
        return GymAutomationReducer.reduce(exit.journal, Event.GraceCheck, s.copy(now = s.now + DEPARTURE_GRACE_MS))
    }

    @Test fun departureRestoresOnlyAfterGrace() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val exit = GymAutomationReducer.reduce(r.journal, Event.Exited(1), s)
        assertEquals(listOf(Effect.ScheduleGraceCheck(1_000 + DEPARTURE_GRACE_MS)), exit.effects)
        val early = GymAutomationReducer.reduce(exit.journal, Event.GraceCheck, s.copy(now = 2_000))
        assertFalse(early.effects.contains(Effect.DisableGym))
        val late = GymAutomationReducer.reduce(exit.journal, Event.GraceCheck, s.copy(now = 1_000 + DEPARTURE_GRACE_MS))
        assertEquals(listOf(Effect.StopHr, Effect.DisableGym), late.effects)
        assertEquals(Presence.OUTSIDE, late.journal.presence)
    }

    @Test fun returningDuringGraceCancelsDeparture() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val exit = GymAutomationReducer.reduce(r.journal, Event.Exited(1), s)
        val back = GymAutomationReducer.reduce(exit.journal, Event.Arrived(1, "v9"), s.copy(now = 5_000))
        assertEquals(listOf(Effect.CancelGraceCheck), back.effects)
        assertEquals("v1", back.journal.visitId)
        assertTrue(GymAutomationReducer.reduce(back.journal, Event.GraceCheck, s.copy(now = 10_000_000)).effects.isEmpty())
    }

    @Test fun unknownSignalIsNeverLeft() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val exit = GymAutomationReducer.reduce(r.journal, Event.Exited(1), s)
        val unknown = GymAutomationReducer.reduce(exit.journal, Event.LocationUnavailable, s)
        assertEquals(listOf(Effect.CancelGraceCheck), unknown.effects)
        val later = GymAutomationReducer.reduce(unknown.journal, Event.GraceCheck, s.copy(now = 99_999_999))
        assertTrue(later.effects.isEmpty())
        assertEquals(Presence.INSIDE, later.journal.presence)
    }

    @Test fun initialExitWithoutVisitIsIgnored() {
        val r = GymAutomationReducer.reduce(Journal(ruleRevision = 1), Event.Exited(1), snap())
        assertTrue(r.effects.isEmpty())
        assertEquals(Presence.UNKNOWN, r.journal.presence)
    }

    @Test fun restoreOffLeavesEverythingRunning() {
        val r = arrive(s = snap(restore = false))
        assertTrue(depart(r.journal, snap(gymOn = true, hrActive = true, restore = false)).effects.isEmpty())
    }

    @Test fun manualGymOffSuppressesForVisitOnly() {
        val r = arrive()
        val off = GymAutomationReducer.reduce(r.journal, Event.ManualGymOff, snap())
        assertTrue(off.journal.gymSuppressed)
        val rec = GymAutomationReducer.reduce(off.journal, Event.Reconcile, snap())
        assertTrue(rec.effects.isEmpty())
        val dwell = GymAutomationReducer.reduce(off.journal, Event.Arrived(1, "v1"), snap())
        assertTrue(dwell.effects.isEmpty())
        assertTrue(depart(off.journal, snap()).effects.isEmpty())
        val left = depart(off.journal, snap()).journal
        val next = GymAutomationReducer.reduce(left, Event.Arrived(1, "v2"), snap())
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), next.effects)
    }

    @Test fun resumeClearsSuppression() {
        val off = GymAutomationReducer.reduce(arrive().journal, Event.ManualGymOff, snap())
        val resumed = GymAutomationReducer.reduce(off.journal, Event.ResumeVisit, snap())
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), resumed.effects)
    }

    @Test fun manualHrStopReleasesOwnership() {
        val stop = GymAutomationReducer.reduce(arrive().journal, Event.ManualHrStop, snap(gymOn = true))
        assertFalse(stop.journal.ownsHr)
        assertEquals(listOf(Effect.DisableGym), depart(stop.journal, snap(gymOn = true)).effects)
    }

    @Test fun staleRevisionCallbacksAreIgnored() {
        val r = GymAutomationReducer.reduce(Journal(ruleRevision = 2), Event.Arrived(1, "v"), snap(revision = 2))
        assertTrue(r.effects.isEmpty())
    }

    @Test fun disabledRuleDoesNothing() {
        assertTrue(arrive(s = snap(enabled = false)).effects.isEmpty())
    }

    @Test fun processDeathSameBootNeverReplaysHr() {
        val r = arrive()
        val cold = GymAutomationReducer.reduce(r.journal, Event.ColdStart, snap(gymOn = true, hrActive = false))
        assertTrue(cold.effects.isEmpty())
        assertFalse(cold.journal.ownsHr)
        assertTrue(cold.journal.hrSuppressed)
        assertTrue(cold.journal.ownsGym)
        assertEquals(listOf(Effect.DisableGym), depart(cold.journal, snap(gymOn = true)).effects)
    }

    @Test fun processDeathRestoresPendingGrace() {
        val exit = GymAutomationReducer.reduce(arrive().journal, Event.Exited(1), snap(gymOn = true))
        val cold = GymAutomationReducer.reduce(exit.journal, Event.ColdStart, snap(now = 2_000, gymOn = true))
        assertEquals(listOf(Effect.ScheduleGraceCheck(1_000 + DEPARTURE_GRACE_MS)), cold.effects)
    }

    @Test fun rebootDiscardsEvidenceButKeepsSuppression() {
        val off = GymAutomationReducer.reduce(arrive().journal, Event.ManualGymOff, snap())
        val exit = GymAutomationReducer.reduce(off.journal, Event.Exited(1), snap())
        val cold = GymAutomationReducer.reduce(exit.journal, Event.ColdStart, snap(boot = "b2"))
        assertTrue(cold.effects.isEmpty())
        assertEquals(Presence.UNKNOWN, cold.journal.presence)
        assertEquals(null, cold.journal.departureDeadline)
        assertTrue(cold.journal.gymSuppressed)
        val dwell = GymAutomationReducer.reduce(cold.journal, Event.Arrived(1, "v3"), snap(boot = "b2"))
        assertTrue(dwell.effects.isEmpty())
        assertEquals("v1", dwell.journal.visitId)
    }

    @Test fun disablingRuleMidVisitRestores() {
        val r = arrive()
        val off = GymAutomationReducer.reduce(r.journal, Event.RuleChanged, snap(enabled = false, gymOn = true, hrActive = true))
        assertEquals(listOf(Effect.StopHr, Effect.DisableGym), off.effects)
        assertEquals(null, off.journal.visitId)
    }
}
