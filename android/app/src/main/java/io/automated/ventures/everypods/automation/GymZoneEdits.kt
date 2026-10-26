/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Pure editing rules for one zone in the editor. Every write changes exactly one field of
 * the *latest stored* zone, so a debounced write can never restore a stale copy of the
 * other fields (position, radius, name).
 */
object GymZoneEdits {
    /** Madrid, the last-resort start view for a new zone. */
    const val FALLBACK_LAT = 40.4168
    const val FALLBACK_LON = -3.7038
    private const val SAME_POSITION_EPS = 1e-6

    fun withRadius(rule: GymAutomationRule, id: String, meters: Int) = edit(rule, id) {
        it.copy(radiusMeters = GymAutomationRule.normalizeRadius(meters))
    }
    fun withName(rule: GymAutomationRule, id: String, name: String) = edit(rule, id) { it.copy(name = name) }
    fun withPosition(rule: GymAutomationRule, id: String, lat: Double, lon: Double) = edit(rule, id) {
        val (la, lo) = GymMapMath.normalize(GymMapMath.clampLatitude(lat), lon)
        it.copy(latitude = la, longitude = lo)
    }

    private fun edit(rule: GymAutomationRule, id: String, f: (GymZone) -> GymZone) =
        rule.copy(zones = rule.zones.map { if (it.id == id) f(it) else it })

    /**
     * Whether a settled map centre should be persisted: only after a real user gesture or an
     * explicit move, and only when it differs from what is stored (opening the editor or the
     * initial camera placement must write nothing).
     */
    fun shouldPersistCenter(armed: Boolean, stored: GymZone?, lat: Double, lon: Double): Boolean {
        if (!armed || stored == null) return false
        val sLat = stored.latitude ?: return true
        val sLon = stored.longitude ?: return true
        return abs(sLat - lat) > SAME_POSITION_EPS || abs(sLon - lon) > SAME_POSITION_EPS
    }

    /** Start view for a zone without a position: current fix, else an existing zone, else Madrid. */
    fun startPosition(current: Pair<Double, Double>?, rule: GymAutomationRule): Pair<Double, Double> =
        current ?: rule.validZones.firstOrNull()?.let { it.latitude!! to it.longitude!! } ?: (FALLBACK_LAT to FALLBACK_LON)
}

/**
 * The radius slider's state. The slider reports continuous values from small drag deltas
 * relative to the value it is given, so the raw value must be kept as-is while dragging;
 * only [meters] is snapped to the 20 m grid (for the label, the circle and persistence).
 * Snapping the bound value on every change made each small delta round back to the same
 * stop, so the thumb never moved.
 */
class GymRadiusSliderState(initialMeters: Int) {
    var raw: Float by mutableFloatStateOf(initialMeters.toFloat()); private set
    val meters: Int get() = GymAutomationRule.normalizeRadius(raw)
    /** Last value written to or confirmed by the store; external changes differ from it. */
    var persisted: Int by mutableIntStateOf(GymAutomationRule.normalizeRadius(initialMeters)); private set

    fun onValueChange(v: Float) { raw = v.coerceIn(GymAutomationRule.MIN_RADIUS_M.toFloat(), GymAutomationRule.MAX_RADIUS_M.toFloat()) }
    /** The value to write, or null when nothing changed. */
    fun pendingWrite(): Int? = meters.takeIf { it != persisted }
    fun markPersisted(m: Int) { persisted = m }
    /** A stored value from elsewhere (debug hook, other screen) replaces the local one; our own echoes do not. */
    fun onStored(m: Int): Boolean {
        if (m == persisted) return false
        persisted = m; raw = m.toFloat(); return true
    }
}
