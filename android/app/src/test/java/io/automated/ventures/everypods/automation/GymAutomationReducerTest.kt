/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymAutomationReducerTest {
    private fun snap(
        now: Long = 1_000, boot: String = "b1", ready: Boolean = true, gymOn: Boolean = false,
        hrActive: Boolean = false, method: String? = "BLUETOOTH", hrAction: Boolean = true,
        restore: Boolean = true, enabled: Boolean = true, revision: Long = 1,
        owner: HeartRateSource? = if (hrActive) HeartRateSource.AUTOMATION else null,
    ) = Snapshot(now, boot, enabled, revision, hrAction, restore, ready, gymOn, hrActive, method, owner)

    private fun arrive(j: Journal = Journal(ruleRevision = 1), s: Snapshot = snap()) =
        GymAutomationReducer.reduce(j, Event.Arrived(1, "v1"), s)

    @Test fun connectedButNotWornKeepsHrPendingAfterGymIsApplied() {
        val waiting = arrive(s = snap().copy(heartRateReady = false))
        assertEquals(listOf(Effect.EnableGym), waiting.effects)
        assertTrue(waiting.journal.applied)
        assertFalse(waiting.journal.hrAttempted)
        val worn = GymAutomationReducer.reduce(waiting.journal, Event.Reconcile, snap(gymOn = true))
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")), worn.effects)
        assertEquals(1, worn.journal.hrStartAttempts)
        val duplicate = GymAutomationReducer.reduce(worn.journal, Event.Reconcile, snap(gymOn = true))
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test fun rejectedStartWaitsForReadinessChangeAndHasFiniteBudget() {
        var journal = arrive().journal.copy(ownsHr = false) // service rejected the start
        repeat(GymAutomationReducer.MAX_HR_START_ATTEMPTS - 1) {
            assertTrue(GymAutomationReducer.reduce(journal, Event.Reconcile, snap(gymOn = true)).effects.isEmpty())
            journal = GymAutomationReducer.reduce(journal, Event.Reconcile,
                snap(gymOn = true).copy(heartRateReady = false)).journal
            val retry = GymAutomationReducer.reduce(journal, Event.Reconcile, snap(gymOn = true))
            assertEquals(listOf(Effect.StartHr("BLUETOOTH")), retry.effects)
            journal = retry.journal.copy(ownsHr = false)
        }
        journal = GymAutomationReducer.reduce(journal, Event.Reconcile,
            snap(gymOn = true, ready = false)).journal
        assertTrue(GymAutomationReducer.reduce(journal, Event.Reconcile, snap(gymOn = true)).effects.isEmpty())
        val resumed = GymAutomationReducer.reduce(journal, Event.ResumeVisit, snap(gymOn = true))
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")), resumed.effects)
    }

    @Test fun manualStopWhilePendingSurvivesWearReconnectAndDuplicateArrival() {
        val waiting = arrive(s = snap().copy(heartRateReady = false))
        val stopped = GymAutomationReducer.reduce(waiting.journal, Event.ManualHrStop, snap(gymOn = true))
        val worn = GymAutomationReducer.reduce(stopped.journal, Event.Reconcile, snap(gymOn = true))
        assertTrue(worn.effects.isEmpty())
        assertTrue(GymAutomationReducer.reduce(worn.journal, Event.Arrived(1, "duplicate"),
            snap(gymOn = true)).effects.isEmpty())
    }

    @Test fun leavingOrDisablingWhilePendingCancelsTheStart() {
        val waiting = arrive(s = snap().copy(heartRateReady = false))
        val exit = GymAutomationReducer.reduce(waiting.journal, Event.Exited(1), snap(gymOn = true))
        assertTrue(GymAutomationReducer.reduce(exit.journal, Event.Reconcile, snap(gymOn = true)).effects.isEmpty())
        val off = GymAutomationReducer.reduce(waiting.journal, Event.RuleChanged,
            snap(gymOn = true, enabled = false))
        assertEquals(listOf(Effect.DisableGym), off.effects)
        assertTrue(GymAutomationReducer.reduce(off.journal, Event.Reconcile,
            snap(gymOn = true, enabled = false)).effects.isEmpty())
    }

    @Test fun coldStartDoesNotReplayAnAttemptedSessionButPendingWearStillWorks() {
        val attempted = arrive().journal.copy(ownsHr = false)
        val cold = GymAutomationReducer.reduce(attempted, Event.ColdStart, snap(gymOn = true))
        assertTrue(cold.journal.hrSuppressed)
        assertTrue(cold.effects.isEmpty())
        val pending = arrive(s = snap().copy(heartRateReady = false)).journal
        val pendingCold = GymAutomationReducer.reduce(pending, Event.ColdStart,
            snap(gymOn = true).copy(heartRateReady = false))
        assertFalse(pendingCold.journal.hrSuppressed)
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")),
            GymAutomationReducer.reduce(pendingCold.journal, Event.Reconcile, snap(gymOn = true)).effects)
    }

    @Test fun validReadingResetsFailureBudgetForLaterWearSessions() {
        val live = GymAutomationReducer.reduce(arrive().journal.copy(hrStartAttempts = 3), Event.Reconcile,
            snap(gymOn = true, hrActive = true).copy(heartRateLive = true))
        assertEquals(0, live.journal.hrStartAttempts)
        val removed = GymAutomationReducer.reduce(live.journal, Event.Reconcile,
            snap(gymOn = true).copy(heartRateReady = false))
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")),
            GymAutomationReducer.reduce(removed.journal, Event.Reconcile, snap(gymOn = true)).effects)
    }

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

    @Test fun arrivalWithoutAirPodsEnablesGymAndConnectingLaterStartsHeartRate() {
        val r = arrive(s = snap(ready = false))
        assertEquals(listOf(Effect.EnableGym), r.effects)
        assertEquals(Presence.INSIDE, r.journal.presence)
        val later = GymAutomationReducer.reduce(r.journal, Event.Reconcile, snap(ready = true, gymOn = true))
        assertEquals(listOf(Effect.StartHr("BLUETOOTH")), later.effects)
    }

    @Test fun manualGymOffWithoutAirPodsRemainsSuppressedAfterConnection() {
        val arrival = arrive(s = snap(ready = false))
        val off = GymAutomationReducer.reduce(arrival.journal, Event.ManualGymOff, snap(ready = false))
        val reconnect = GymAutomationReducer.reduce(off.journal, Event.Reconcile, snap(ready = true))
        assertTrue(reconnect.journal.gymSuppressed)
        assertTrue(reconnect.effects.isEmpty())
    }

    @Test fun gymEnabledWithoutAirPodsIsRestoredOnDeparture() {
        val arrival = arrive(s = snap(ready = false))
        val departed = depart(arrival.journal, snap(ready = false, gymOn = true))
        assertEquals(listOf(Effect.DisableGym), departed.effects)
    }

    @Test fun automationAnnouncementWaitsForWornAudioAndRunsOnlyOnce() {
        val arrived = arrive(s = snap(ready = false))
        assertTrue(arrived.journal.gymAnnouncementPending)
        val connected = GymAutomationReducer.reduce(arrived.journal, Event.Reconcile, snap(gymOn = true))
        assertTrue(connected.journal.gymAnnouncementPending)
        val worn = GymAutomationReducer.reduce(connected.journal, Event.Reconcile,
            snap(gymOn = true).copy(gymAnnouncementReady = true))
        assertEquals(listOf(Effect.AnnounceGym), worn.effects)
        assertFalse(worn.journal.gymAnnouncementPending)
        val again = GymAutomationReducer.reduce(worn.journal, Event.Reconcile,
            snap(gymOn = true).copy(gymAnnouncementReady = true))
        assertTrue(again.effects.isEmpty())
    }

    @Test fun pendingAnnouncementIsCancelledByManualOffOrDeparture() {
        val arrived = arrive(s = snap(ready = false))
        val off = GymAutomationReducer.reduce(arrived.journal, Event.ManualGymOff, snap(ready = false))
        assertFalse(off.journal.gymAnnouncementPending)
        assertTrue(GymAutomationReducer.reduce(off.journal, Event.Reconcile,
            snap().copy(gymAnnouncementReady = true)).effects.isEmpty())
        val departed = depart(arrived.journal, snap(ready = false, gymOn = true))
        assertFalse(departed.journal.gymAnnouncementPending)
    }

    @Test fun preexistingGymModeDoesNotQueueAutomationAnnouncement() {
        val arrived = arrive(s = snap(gymOn = true).copy(gymAnnouncementReady = true))
        assertFalse(arrived.journal.gymAnnouncementPending)
        assertFalse(arrived.effects.contains(Effect.AnnounceGym))
    }

    private fun depart(j: Journal, s: Snapshot): Result {
        return GymAutomationReducer.reduce(j, Event.Exited(1), s)
    }

    @Test fun departureRestoresImmediately() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val exit = GymAutomationReducer.reduce(r.journal, Event.Exited(1), s)
        assertEquals(listOf(Effect.StopHr, Effect.DisableGym), exit.effects)
        assertEquals(Presence.OUTSIDE, exit.journal.presence)
    }

    @Test fun returningAfterDepartureStartsNewVisit() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val exit = GymAutomationReducer.reduce(r.journal, Event.Exited(1), s)
        val back = GymAutomationReducer.reduce(exit.journal, Event.Arrived(1, "v9"), s.copy(now = 5_000))
        assertEquals("v9", back.journal.visitId)
        assertEquals(Presence.INSIDE, back.journal.presence)
    }

    @Test fun unknownSignalIsNeverLeft() {
        val r = arrive()
        val s = snap(gymOn = true, hrActive = true)
        val unknown = GymAutomationReducer.reduce(r.journal, Event.LocationUnavailable, s)
        assertTrue(unknown.effects.isEmpty())
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

    @Test fun processDeathCompletesLegacyPendingDeparture() {
        val old = arrive().journal.copy(departureDeadline = 50_000)
        val cold = GymAutomationReducer.reduce(old, Event.ColdStart, snap(now = 2_000, gymOn = true))
        assertTrue(cold.effects.contains(Effect.CancelGraceCheck))
        assertTrue(cold.effects.contains(Effect.DisableGym))
    }

    @Test fun rebootDiscardsEvidenceButKeepsSuppression() {
        val off = GymAutomationReducer.reduce(arrive().journal, Event.ManualGymOff, snap())
        val cold = GymAutomationReducer.reduce(off.journal, Event.ColdStart, snap(boot = "b2"))
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

    // --- HR ownership by entry point ---

    @Test fun manualRestartTakesOverOwnershipSoDepartureLeavesHr() {
        val r = arrive()
        assertTrue(r.journal.ownsHr)
        // User stopped and restarted from the case shortcut (or switched transport).
        val rec = GymAutomationReducer.reduce(r.journal, Event.Reconcile,
            snap(gymOn = true, hrActive = true, owner = HeartRateSource.CASE_SHORTCUT))
        assertFalse(rec.journal.ownsHr)
        val left = depart(rec.journal, snap(gymOn = true, hrActive = true, owner = HeartRateSource.CASE_SHORTCUT))
        assertEquals(listOf(Effect.DisableGym), left.effects)
    }

    @Test fun departureNeverStopsHrOwnedByAnotherEntryPoint() {
        val r = arrive()
        // Even if the journal was not reconciled yet, a foreign live owner blocks StopHr.
        val left = depart(r.journal, snap(gymOn = true, hrActive = true, owner = HeartRateSource.HEART_RATE_SCREEN))
        assertFalse(left.effects.contains(Effect.StopHr))
    }

    @Test fun ownerRecordFromRecoveryKeepsOwnership() {
        val r = arrive()
        val rec = GymAutomationReducer.reduce(r.journal, Event.Reconcile,
            snap(gymOn = true, hrActive = true, owner = HeartRateSource.AUTOMATION))
        assertTrue(rec.journal.ownsHr)
    }

    // --- Bounded reconnect presence fix ---

    @Test fun needsFixWhenReadyAndNotInside() {
        assertTrue(GymAutomationReducer.needsPresenceFix(Journal(), snap()))
        assertTrue(GymAutomationReducer.needsPresenceFix(Journal(), snap(ready = false)))
        assertFalse(GymAutomationReducer.needsPresenceFix(Journal(), snap(enabled = false)))
        assertTrue(GymAutomationReducer.needsPresenceFix(Journal(presence = Presence.INSIDE), snap()))
        assertTrue(GymAutomationReducer.needsPresenceFix(Journal(presence = Presence.OUTSIDE), snap()))
        assertTrue(GymAutomationReducer.needsPresenceFix(Journal(departureDeadline = 5), snap()))
    }

    @Test fun insideFixWithoutAirPodsEnablesGym() {
        val r = GymAutomationReducer.reduce(Journal(presence = Presence.OUTSIDE),
            Event.PresenceFix(1, Presence.INSIDE, "new"), snap(ready = false))
        assertEquals(listOf(Effect.EnableGym), r.effects)
        assertTrue(r.journal.ownsGym)
        assertFalse(r.journal.hrAttempted)
    }

    @Test fun insideFixAfterPreviousVisitStartsNewVisit() {
        val outside = Journal(presence = Presence.OUTSIDE)
        val r = GymAutomationReducer.reduce(outside, Event.PresenceFix(1, Presence.INSIDE, "new"), snap())
        assertEquals(Presence.INSIDE, r.journal.presence)
        assertEquals("new", r.journal.visitId)
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), r.effects)
    }

    @Test fun insideFixWithoutVisitStartsOneVisitOnce() {
        val r = GymAutomationReducer.reduce(Journal(), Event.PresenceFix(1, Presence.INSIDE, "f1"), snap())
        assertEquals(Presence.INSIDE, r.journal.presence)
        assertEquals(listOf(Effect.EnableGym, Effect.StartHr("BLUETOOTH")), r.effects)
        val again = GymAutomationReducer.reduce(r.journal, Event.PresenceFix(1, Presence.INSIDE, "f2"),
            snap(gymOn = true, hrActive = true))
        assertTrue(again.effects.isEmpty())
        val dwell = GymAutomationReducer.reduce(r.journal, Event.Arrived(1, "v9"), snap(gymOn = true, hrActive = true))
        assertTrue(dwell.effects.isEmpty())
    }

    @Test fun insideFixAfterProcessDeathNeverReplaysHrOrClearsSuppression() {
        val active = arrive().journal.copy(presence = Presence.UNKNOWN)
        val cold = GymAutomationReducer.reduce(active, Event.ColdStart, snap(boot = "b2", gymOn = true))
        val fix = GymAutomationReducer.reduce(cold.journal, Event.PresenceFix(1, Presence.INSIDE, "f"),
            snap(boot = "b2", gymOn = true))
        assertEquals(Presence.INSIDE, fix.journal.presence)
        assertEquals("v1", fix.journal.visitId)
        assertTrue(fix.journal.hrSuppressed)
        assertTrue(fix.effects.isEmpty())
    }

    @Test fun outsideFixForRememberedVisitDepartsImmediately() {
        val j = arrive().journal.copy(presence = Presence.UNKNOWN)
        val r = GymAutomationReducer.reduce(j, Event.PresenceFix(1, Presence.OUTSIDE, "f"), snap(now = 10, gymOn = true, hrActive = true))
        assertEquals(listOf(Effect.StopHr, Effect.DisableGym), r.effects)
        val back = GymAutomationReducer.reduce(r.journal, Event.Arrived(1, "v2"), snap(now = 20, gymOn = true, hrActive = true))
        assertEquals("v2", back.journal.visitId)
    }

    @Test fun outsideFixWithoutVisitOnlyRecordsPresence() {
        val r = GymAutomationReducer.reduce(Journal(), Event.PresenceFix(1, Presence.OUTSIDE, "f"), snap())
        assertEquals(Presence.OUTSIDE, r.journal.presence)
        assertTrue(r.effects.isEmpty())
        assertTrue(GymAutomationReducer.needsPresenceFix(r.journal, snap()))
    }

    @Test fun ambiguousOrStaleFixChangesNothing() {
        val j = arrive().journal.copy(presence = Presence.UNKNOWN)
        val r = GymAutomationReducer.reduce(j, Event.PresenceFix(1, Presence.UNKNOWN, "f"), snap(gymOn = true))
        assertEquals(j, r.journal)
        assertTrue(r.effects.isEmpty())
        val old = GymAutomationReducer.reduce(j, Event.PresenceFix(0, Presence.OUTSIDE, "f"), snap(gymOn = true))
        assertTrue(old.effects.isEmpty())
    }

    @Test fun outsideFixForKnownInsideDepartsImmediately() {
        val inside = arrive().journal
        val r = GymAutomationReducer.reduce(inside, Event.PresenceFix(1, Presence.OUTSIDE, "f"), snap(gymOn = true))
        assertEquals(listOf(Effect.DisableGym), r.effects)
        assertEquals(Presence.OUTSIDE, r.journal.presence)
    }
}
