/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

/**
 * Pure decision engine for the location-triggered Gym Mode rule.
 *
 * No Android types live here so every rule (arrival, departure grace, AirPods
 * arriving later, manual override, ownership, process death) is unit-testable.
 * The coordinator feeds events plus a [Snapshot] of the live world and executes
 * the returned [Effect]s; the returned [Journal] is what gets persisted.
 */
enum class Presence { UNKNOWN, INSIDE, OUTSIDE }

/** The minimal per-visit journal persisted across process death. No coordinates, no history. */
data class Journal(
    val ruleRevision: Long = 0,
    val presence: Presence = Presence.UNKNOWN,
    val visitId: String? = null,
    /** Boot the presence/deadline evidence belongs to; a different boot discards it. */
    val bootId: String? = null,
    /** Elapsed-realtime deadline of a pending departure (outer EXIT seen, grace running). */
    val departureDeadline: Long? = null,
    /** Gym Mode was already on when this visit applied its actions. */
    val gymWasOn: Boolean = false,
    /** This visit switched Gym Mode on and still owns that change. */
    val ownsGym: Boolean = false,
    /** This visit started heart rate and still owns that session. */
    val ownsHr: Boolean = false,
    /** Actions were applied for this visit (prevents duplicate runs). */
    val applied: Boolean = false,
    /** User turned Gym Mode off during this visit: no re-activation until the visit ends. */
    val gymSuppressed: Boolean = false,
    /** User stopped heart rate during this visit (or it was interrupted by a restart). */
    val hrSuppressed: Boolean = false,
    val locationUnavailable: Boolean = false,
)

/** Live state the coordinator reads just before reducing. */
data class Snapshot(
    val now: Long,
    val bootId: String,
    val ruleEnabled: Boolean,
    val ruleRevision: Long,
    val hrActionEnabled: Boolean,
    val restoreOnExit: Boolean,
    val airPodsReady: Boolean,
    val gymOn: Boolean,
    /** Monitoring, recovering or sharing — anything the case shortcut would treat as "active". */
    val hrActive: Boolean,
    /** Explicit sharing selection from HeartRateSharingPrefs.configuredMethod(); null means none. */
    val hrSelectedMethod: String?,
)

sealed interface Event {
    /** Inner-boundary DWELL for [revision]. */
    data class Arrived(val revision: Long, val visitId: String) : Event
    /** Outer-boundary EXIT for [revision]: starts the departure grace period. */
    data class Exited(val revision: Long) : Event
    /** Grace timer fired (or reconciliation found the deadline elapsed). */
    data object GraceCheck : Event
    /** Geofence error, provider off, permission revoked: never counts as leaving. */
    data object LocationUnavailable : Event
    /** AirPods connection/readiness or other live state changed. */
    data object Reconcile : Event
    data object ManualGymOff : Event
    data object ManualHrStop : Event
    /** "Resume for this visit": clears suppression and reruns readiness. */
    data object ResumeVisit : Event
    /** Process start; journal restored from storage. */
    data object ColdStart : Event
    /** Rule edited/disabled/deleted. */
    data object RuleChanged : Event
}

sealed interface Effect {
    data object EnableGym : Effect
    data object DisableGym : Effect
    data class StartHr(val method: String) : Effect
    data object StopHr : Effect
    data class ScheduleGraceCheck(val atElapsed: Long) : Effect
    data object CancelGraceCheck : Effect
}

data class Result(val journal: Journal, val effects: List<Effect>)

object GymAutomationReducer {
    const val DEPARTURE_GRACE_MS = 3 * 60_000L

    fun reduce(j: Journal, e: Event, s: Snapshot): Result {
        val fx = mutableListOf<Effect>()
        var n = j
        when (e) {
            is Event.Arrived -> {
                if (!s.ruleEnabled || e.revision != s.ruleRevision) return Result(j, emptyList())
                if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                n = if (n.presence == Presence.INSIDE && n.visitId != null) {
                    n.copy(departureDeadline = null, locationUnavailable = false, bootId = s.bootId)
                } else if (n.visitId != null && n.presence == Presence.UNKNOWN) {
                    // Same visit re-confirmed after restart/re-registration: keep suppression.
                    n.copy(presence = Presence.INSIDE, departureDeadline = null, locationUnavailable = false, bootId = s.bootId)
                } else {
                    Journal(ruleRevision = s.ruleRevision, presence = Presence.INSIDE, visitId = e.visitId, bootId = s.bootId)
                }
                n = apply(n, s, fx)
            }
            is Event.Exited -> {
                if (e.revision != s.ruleRevision) return Result(j, emptyList())
                // Only a visit that was actually inside can end; an initial EXIT is ignored.
                if (n.presence != Presence.INSIDE || n.visitId == null || n.departureDeadline != null) return Result(j, emptyList())
                val at = s.now + DEPARTURE_GRACE_MS
                n = n.copy(departureDeadline = at, bootId = s.bootId)
                fx += Effect.ScheduleGraceCheck(at)
            }
            Event.GraceCheck -> {
                val deadline = n.departureDeadline ?: return Result(j, emptyList())
                if (n.bootId != s.bootId) return Result(n.copy(departureDeadline = null), emptyList())
                if (s.now < deadline) {
                    fx += Effect.ScheduleGraceCheck(deadline)
                } else {
                    n = depart(n, s, fx)
                }
            }
            Event.LocationUnavailable -> {
                // Unknown is never "left": cancel any pending departure, keep the visit.
                if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                n = n.copy(locationUnavailable = true, departureDeadline = null)
            }
            Event.Reconcile -> {
                if (!s.ruleEnabled) return Result(j, emptyList())
                if (n.ownsHr && !s.hrActive) n = n.copy(ownsHr = false)
                if (n.presence == Presence.INSIDE) n = apply(n, s, fx)
            }
            Event.ManualGymOff -> {
                if (n.visitId != null && n.presence != Presence.OUTSIDE) {
                    // Turning Gym Mode off (existing listener) already stops gym-owned HR.
                    n = n.copy(gymSuppressed = true, ownsGym = false, ownsHr = false)
                }
            }
            Event.ManualHrStop -> {
                if (n.visitId != null) n = n.copy(hrSuppressed = true, ownsHr = false)
            }
            Event.ResumeVisit -> {
                if (n.visitId == null || n.presence != Presence.INSIDE) return Result(j, emptyList())
                n = apply(n.copy(gymSuppressed = false, hrSuppressed = false, applied = false), s, fx)
            }
            Event.ColdStart -> {
                // A heart-rate session never survives process death (memory-only intent):
                // the rule no longer owns one and must not replay it.
                n = n.copy(ownsHr = false, hrSuppressed = n.hrSuppressed || j.ownsHr)
                if (n.bootId != s.bootId) {
                    // Previous boot's evidence is meaningless; keep config + suppression.
                    n = n.copy(presence = if (n.visitId != null) Presence.UNKNOWN else n.presence,
                        departureDeadline = null, bootId = s.bootId)
                } else if (n.departureDeadline != null) {
                    fx += Effect.ScheduleGraceCheck(n.departureDeadline!!)
                }
                if (n.presence == Presence.UNKNOWN && n.visitId != null) {
                    // Do not act on a remembered visit; wait for a fresh DWELL.
                } else if (n.presence == Presence.INSIDE) {
                    n = apply(n, s, fx)
                }
            }
            Event.RuleChanged -> {
                if (!s.ruleEnabled || s.ruleRevision != n.ruleRevision) {
                    if (n.visitId != null) n = depart(n, s, fx)
                    n = Journal(ruleRevision = s.ruleRevision, bootId = s.bootId)
                }
            }
        }
        return Result(n, fx)
    }

    private fun apply(j: Journal, s: Snapshot, fx: MutableList<Effect>): Journal {
        if (!s.ruleEnabled || j.gymSuppressed || j.departureDeadline != null) return j
        if (!s.airPodsReady) return j // wait; AirPods connecting later triggers Reconcile
        var n = j
        if (!n.applied) {
            n = n.copy(applied = true, gymWasOn = s.gymOn)
            if (!s.gymOn) {
                fx += Effect.EnableGym
                n = n.copy(ownsGym = true)
            }
            if (s.hrActionEnabled && !n.hrSuppressed && !s.hrActive) {
                // Shortcut semantics, locked decision: only start the method the user selected.
                s.hrSelectedMethod?.let { fx += Effect.StartHr(it); n = n.copy(ownsHr = true) }
            }
        }
        return n
    }

    private fun depart(j: Journal, s: Snapshot, fx: MutableList<Effect>): Journal {
        if (s.restoreOnExit) {
            if (j.ownsHr && s.hrActive) fx += Effect.StopHr
            if (j.ownsGym && !j.gymWasOn && s.gymOn) fx += Effect.DisableGym
        }
        return Journal(ruleRevision = j.ruleRevision, presence = Presence.OUTSIDE, bootId = s.bootId)
    }
}
