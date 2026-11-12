/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

/**
 * Pure decision engine for the location-triggered Gym Mode rule.
 *
 * No Android types live here so every rule (arrival, departure, AirPods
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
    /**
     * Zones (id to geometry revision) whose inner boundary this visit is inside. The visit
     * ends the visit when the last zone is left.
     */
    val insideZones: Map<String, Long> = emptyMap(),
    /** Decoded from a schema-1 journal (no zone set yet); resolved by the repository. */
    val legacyInside: Boolean = false,
    /** One start per wear/connection readiness period, with a finite per-visit budget. */
    val hrAttempted: Boolean = false,
    val hrStartAttempts: Int = 0,
    /** One activation announcement waiting for worn, connected AirPods. */
    val gymAnnouncementPending: Boolean = false,
    val lastFixElapsedMs: Long? = null,
    val transitionElapsedMs: Map<String, Long> = emptyMap(),
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
    /** Live owner of the running heart-rate session (persisted by the service), else null. */
    val hrOwner: HeartRateSource? = null,
    /** Geometry revision of every registrable zone. Empty means a single zone keyed by [ruleRevision]. */
    val zoneRevisions: Map<String, Long> = emptyMap(),
    /** Current-connection wear evidence and HR capability/ownership, independent of Gym Mode. */
    val heartRateReady: Boolean = true,
    val heartRateLive: Boolean = false,
    val gymAnnouncementReady: Boolean = false,
) {
    /** Whether a transition for [zoneId] at [revision] belongs to the current registration. */
    fun isCurrent(zoneId: String, revision: Long): Boolean =
        if (zoneRevisions.isEmpty()) revision == ruleRevision else zoneRevisions[zoneId] == revision
}

sealed interface Event {
    /** Inner-boundary arrival for [revision]. */
    data class Arrived(val revision: Long, val visitId: String, val zoneId: String = SINGLE_ZONE, val observedAt: Long? = null) : Event
    /** Outer-boundary EXIT for [revision]. */
    data class Exited(val revision: Long, val zoneId: String = SINGLE_ZONE, val observedAt: Long? = null) : Event
    /** Grace timer fired (or reconciliation found the deadline elapsed). */
    data object GraceCheck : Event
    /** Geofence error, provider off, permission revoked: never counts as leaving. */
    data object LocationUnavailable : Event
    /** AirPods connection/readiness or other live state changed. */
    data object Reconcile : Event
    /** App entered the foreground; request a bounded location check if needed. */
    data object ForegroundCheck : Event
    data object ManualGymOff : Event
    data object ManualHrStop : Event
    /** "Resume for this visit": clears suppression and reruns readiness. */
    data object ResumeVisit : Event
    /**
     * Result of one bounded current-location fix taken on reconnect/restart while presence
     * was UNKNOWN or OUTSIDE. [presence] is UNKNOWN when the fix was stale, inaccurate or missing.
     */
    data class PresenceFix(
        val revision: Long, val presence: Presence, val visitId: String,
        /** Zones (id to revision) the fix placed us inside; only meaningful for INSIDE. */
        val zones: Map<String, Long> = emptyMap(),
        val observedAt: Long? = null,
        val outsideZoneIds: Set<String> = emptySet(),
    ) : Event
    /** Process start; journal restored from storage. */
    data object ColdStart : Event
    /** Rule edited/disabled, or zones added, edited or removed. */
    data object RuleChanged : Event

    companion object {
        /** Zone key used when a snapshot has no per-zone revisions (single-zone callers/tests). */
        const val SINGLE_ZONE = ""
    }
}

sealed interface Effect {
    data object EnableGym : Effect
    data object DisableGym : Effect
    data class StartHr(val method: String) : Effect
    data object StopHr : Effect
    data object AnnounceGym : Effect
    data object CancelGraceCheck : Effect
}

data class Result(val journal: Journal, val effects: List<Effect>)

object GymAutomationReducer {
    const val MAX_HR_START_ATTEMPTS = 3

    /** Foreground, reconnect and recovery checks do not require connected AirPods. */
    fun needsPresenceFix(j: Journal, s: Snapshot): Boolean = s.ruleEnabled

    fun reduce(j: Journal, e: Event, s: Snapshot): Result {
        val fx = mutableListOf<Effect>()
        if (isOlderEvidence(j, e, s)) return Result(j, emptyList())
        var n = j
        when (e) {
            is Event.Arrived -> {
                if (!s.ruleEnabled || !s.isCurrent(e.zoneId, e.revision)) return Result(j, emptyList())
                if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                n = if (n.presence == Presence.INSIDE && n.visitId != null) {
                    n.copy(departureDeadline = null, locationUnavailable = false, bootId = s.bootId)
                } else if (n.visitId != null && n.presence == Presence.UNKNOWN) {
                    // Same visit re-confirmed after restart/re-registration: keep suppression.
                    n.copy(presence = Presence.INSIDE, departureDeadline = null, locationUnavailable = false, bootId = s.bootId)
                } else {
                    Journal(ruleRevision = s.ruleRevision, presence = Presence.INSIDE, visitId = e.visitId, bootId = s.bootId)
                }
                n = n.copy(insideZones = n.insideZones + (e.zoneId to e.revision),
                    lastFixElapsedMs = j.lastFixElapsedMs, transitionElapsedMs = j.transitionElapsedMs)
                n = rememberTransition(n, e.zoneId, e.observedAt)
                n = apply(n, s, fx)
            }
            is Event.Exited -> {
                if (!s.ruleEnabled || !s.isCurrent(e.zoneId, e.revision)) return Result(j, emptyList())
                n = rememberTransition(n, e.zoneId, e.observedAt)
                // UNKNOWN needs a fix against all zones before ending a remembered visit.
                if (n.presence != Presence.INSIDE || n.visitId == null || e.zoneId !in n.insideZones) {
                    return Result(n, emptyList())
                }
                // Overlapping zones: leaving one while still inside another keeps the visit.
                n = n.copy(insideZones = n.insideZones - e.zoneId)
                if (n.insideZones.isNotEmpty()) return Result(n, emptyList())
                if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                n = depart(n, s, fx)
            }
            Event.GraceCheck -> {
                n.departureDeadline ?: return Result(j, emptyList())
                if (n.bootId != s.bootId) return Result(n.copy(departureDeadline = null), emptyList())
                fx += Effect.CancelGraceCheck
                n = depart(n, s, fx)
            }
            Event.LocationUnavailable -> {
                // Unknown is never "left": cancel any pending departure, keep the visit.
                if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                n = n.copy(locationUnavailable = true, departureDeadline = null)
            }
            is Event.PresenceFix -> {
                if (!s.ruleEnabled || e.revision != s.ruleRevision) return Result(j, emptyList())
                when (e.presence) {
                    Presence.UNKNOWN -> return Result(j, emptyList()) // ambiguous stays unknown
                    Presence.INSIDE -> {
                        if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                        n = if (n.visitId != null) {
                            // Remembered visit confirmed: keep its suppression and applied flag (no replay).
                            n.copy(presence = Presence.INSIDE, departureDeadline = null, locationUnavailable = false, bootId = s.bootId)
                        } else {
                            Journal(ruleRevision = s.ruleRevision, presence = Presence.INSIDE, visitId = e.visitId, bootId = s.bootId)
                        }
                        val confirmedInside = e.zones.filter { (id, rev) -> s.isCurrent(id, rev) }
                        n = n.copy(insideZones = (n.insideZones - e.outsideZoneIds) + confirmedInside)
                        n = apply(n, s, fx)
                    }
                    Presence.OUTSIDE -> {
                        if (n.visitId != null) {
                            if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                            n = depart(n, s, fx)
                        } else {
                            n = n.copy(presence = Presence.OUTSIDE, locationUnavailable = false, bootId = s.bootId)
                        }
                    }
                }
                val observedAt = e.observedAt
                if (e.presence == Presence.OUTSIDE) {
                    n = n.copy(lastFixElapsedMs = observedAt ?: j.lastFixElapsedMs)
                } else {
                    val confirmedZones = e.zones.keys + e.outsideZoneIds
                    val times = if (observedAt != null) confirmedZones.associateWith { observedAt } else emptyMap()
                    n = n.copy(lastFixElapsedMs = j.lastFixElapsedMs,
                        transitionElapsedMs = j.transitionElapsedMs + times)
                }
            }
            Event.Reconcile -> {
                if (!s.ruleEnabled) return Result(j, emptyList())
                // Ownership ends when the session ends or another entry point took it over
                // (manual restart, transport switch, monitoring-only switch).
                if (n.ownsHr && (!s.hrActive || s.hrOwner != HeartRateSource.AUTOMATION)) n = n.copy(ownsHr = false)
                if (n.presence == Presence.INSIDE) n = apply(n, s, fx)
            }
            Event.ForegroundCheck -> Unit
            Event.ManualGymOff -> {
                if (n.visitId != null && n.presence != Presence.OUTSIDE) {
                    // Turning Gym Mode off (existing listener) already stops gym-owned HR.
                    n = n.copy(gymSuppressed = true, ownsGym = false, ownsHr = false, gymAnnouncementPending = false)
                }
            }
            Event.ManualHrStop -> {
                if (n.visitId != null) n = n.copy(hrSuppressed = true, ownsHr = false)
            }
            Event.ResumeVisit -> {
                if (n.visitId == null || n.presence != Presence.INSIDE) return Result(j, emptyList())
                n = apply(n.copy(gymSuppressed = false, hrSuppressed = false, applied = false,
                    hrAttempted = false, hrStartAttempts = 0), s, fx)
            }
            Event.ColdStart -> {
                // A heart-rate session never survives process death (memory-only intent):
                // the rule no longer owns one and must not replay it.
                n = n.copy(ownsHr = false, hrSuppressed = n.hrSuppressed || j.ownsHr || j.hrAttempted)
                if (n.bootId != s.bootId) {
                    // Previous boot's evidence is meaningless; keep config + suppression.
                    n = n.copy(presence = if (n.visitId != null) Presence.UNKNOWN else n.presence,
                        departureDeadline = null, bootId = s.bootId, insideZones = emptyMap(),
                        lastFixElapsedMs = null, transitionElapsedMs = emptyMap())
                } else if (n.departureDeadline != null) {
                    // Complete an already-confirmed departure left by an older build.
                    fx += Effect.CancelGraceCheck
                    n = depart(n, s, fx)
                }
                if (n.presence == Presence.UNKNOWN && n.visitId != null) {
                    // Do not act on a remembered visit; wait for fresh location evidence.
                } else if (n.presence == Presence.INSIDE) {
                    n = apply(n, s, fx)
                }
            }
            Event.RuleChanged -> {
                if (!s.ruleEnabled) {
                    // Automation switched off: end any visit (restoring what it owns) and reset.
                    if (n.visitId != null) n = depart(n, s, fx)
                    n = Journal(ruleRevision = s.ruleRevision, bootId = s.bootId)
                } else {
                    // Zone added/edited/removed: never reset the visit. A zone that was removed
                    // or moved/resized no longer counts as "inside"; its new geofence (initial
                    // ENTER) re-adds it if we are still there.
                    val kept = n.insideZones.filter { (id, rev) -> s.isCurrent(id, rev) }
                    val lostLast = n.insideZones.isNotEmpty() && kept.isEmpty()
                    n = n.copy(ruleRevision = s.ruleRevision, insideZones = kept,
                        transitionElapsedMs = n.transitionElapsedMs.filterKeys { it in s.zoneRevisions })
                    if (lostLast && n.presence == Presence.INSIDE && n.visitId != null) {
                        // A geometry edit is not evidence of departure; request a fresh fix.
                        if (n.departureDeadline != null) fx += Effect.CancelGraceCheck
                        n = n.copy(presence = Presence.UNKNOWN, departureDeadline = null)
                    }
                }
            }
        }
        if (n.gymAnnouncementPending && n.ownsGym && !n.gymSuppressed && n.presence == Presence.INSIDE &&
            n.departureDeadline == null && (s.gymOn || Effect.EnableGym in fx) && s.gymAnnouncementReady) {
            n = n.copy(gymAnnouncementPending = false)
            fx += Effect.AnnounceGym
        }
        return Result(n, fx)
    }

    fun isOlderEvidence(j: Journal, event: Event, s: Snapshot): Boolean {
        if (j.bootId != s.bootId) return false
        val observedAt = when (event) {
            is Event.Arrived -> event.observedAt
            is Event.Exited -> event.observedAt
            is Event.PresenceFix -> event.observedAt
            else -> null
        } ?: return false
        val previousTransition = when (event) {
            is Event.Arrived -> j.transitionElapsedMs[event.zoneId]
            is Event.Exited -> j.transitionElapsedMs[event.zoneId]
            is Event.PresenceFix -> j.transitionElapsedMs.values.maxOrNull()
            else -> null
        }
        return observedAt < maxOf(j.lastFixElapsedMs ?: 0L, previousTransition ?: 0L)
    }

    private fun rememberTransition(j: Journal, zoneId: String, observedAt: Long?): Journal =
        if (observedAt == null) j else j.copy(transitionElapsedMs = j.transitionElapsedMs + (zoneId to observedAt))

    private fun apply(j: Journal, s: Snapshot, fx: MutableList<Effect>): Journal {
        if (!s.ruleEnabled || j.gymSuppressed || j.locationUnavailable || j.departureDeadline != null) return j
        // Losing readiness rearms a pending start; repeated reports while ready do not.
        var n = if (!s.airPodsReady || !s.heartRateReady) j.copy(hrAttempted = false) else j
        if (!n.applied) {
            n = n.copy(applied = true, gymWasOn = s.gymOn)
            if (!s.gymOn) {
                fx += Effect.EnableGym
                n = n.copy(ownsGym = true, gymAnnouncementPending = true)
            }
        }
        if (!s.airPodsReady) return n
        // Gym Mode can already be applied while heart rate is still waiting for wear.
        // A pre-existing session is left alone and must not be restarted if it ends.
        if (s.hrActive) return n.copy(hrAttempted = true,
            hrStartAttempts = if (s.heartRateLive) 0 else n.hrStartAttempts)
        if (s.hrActionEnabled && !n.hrSuppressed && s.heartRateReady && !n.hrAttempted &&
            n.hrStartAttempts < MAX_HR_START_ATTEMPTS) {
            s.hrSelectedMethod?.let {
                fx += Effect.StartHr(it)
                n = n.copy(ownsHr = true, hrAttempted = true, hrStartAttempts = n.hrStartAttempts + 1)
            }
        }
        return n
    }

    private fun depart(j: Journal, s: Snapshot, fx: MutableList<Effect>): Journal {
        if (s.restoreOnExit) {
            if (j.ownsHr && s.hrActive && s.hrOwner == HeartRateSource.AUTOMATION) fx += Effect.StopHr
            if (j.ownsGym && !j.gymWasOn && s.gymOn) fx += Effect.DisableGym
        }
        return Journal(ruleRevision = j.ruleRevision, presence = Presence.OUTSIDE, bootId = s.bootId,
            lastFixElapsedMs = j.lastFixElapsedMs, transitionElapsedMs = j.transitionElapsedMs)
    }
}
