/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

/** Pure rules for turning one current-location fix into presence evidence (plan §7). */
object PresenceEvidence {
    /** A fix older than this (elapsed-realtime, same boot) is not evidence. */
    const val MAX_FIX_AGE_MS = 2 * 60_000L
    /** Hard cap on waiting for a fix. */
    const val FIX_TIMEOUT_MS = 15_000L
    /** At most one fix per this interval per process: no polling loops. */
    const val FIX_COOLDOWN_MS = 2 * 60_000L

    /**
     * INSIDE only if the whole accuracy circle is within the arrival radius, OUTSIDE only if
     * it is wholly beyond the outer (departure) boundary; everything else is UNKNOWN.
     */
    fun classify(distanceM: Double, accuracyM: Double, ageMs: Long, radiusM: Int, exitRadiusM: Int): Presence {
        if (ageMs < 0 || ageMs > MAX_FIX_AGE_MS) return Presence.UNKNOWN
        if (accuracyM.isNaN() || accuracyM <= 0.0 || distanceM.isNaN() || distanceM < 0.0) return Presence.UNKNOWN
        return when {
            distanceM + accuracyM <= radiusM -> Presence.INSIDE
            distanceM - accuracyM >= exitRadiusM -> Presence.OUTSIDE
            else -> Presence.UNKNOWN
        }
    }

    /**
     * Combines per-zone evidence: INSIDE if inside any zone (with those zones), OUTSIDE only
     * if clearly outside every zone, otherwise UNKNOWN.
     */
    fun combine(perZone: List<Triple<String, Long, Presence>>): Pair<Presence, Map<String, Long>> {
        val inside = perZone.filter { it.third == Presence.INSIDE }.associate { it.first to it.second }
        return when {
            inside.isNotEmpty() -> Presence.INSIDE to inside
            perZone.isNotEmpty() && perZone.all { it.third == Presence.OUTSIDE } -> Presence.OUTSIDE to emptyMap()
            else -> Presence.UNKNOWN to emptyMap()
        }
    }

    fun canRequest(now: Long, lastRequestAt: Long?, inFlight: Boolean): Boolean =
        !inFlight && (lastRequestAt == null || now - lastRequestAt >= FIX_COOLDOWN_MS)
}
