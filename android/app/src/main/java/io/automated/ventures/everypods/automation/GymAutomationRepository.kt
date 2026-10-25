/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One saved gym location. Stored only on this device; never logged with coordinates.
 * [revision] changes only when the geometry (position or radius) changes, so renaming
 * never re-registers geofences and never affects a visit.
 */
data class GymZone(
    val id: String = UUID.randomUUID().toString(),
    /** Blank means "use the localized default name" (gym_zone_default_name). */
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = GymAutomationRule.DEFAULT_RADIUS_M,
    val revision: Long = 1,
) {
    val hasLocation get() = latitude != null && longitude != null &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    /**
     * Radius actually registered for the arrival geofence. Play Services geofences are
     * unreliable below ~100 m, so small zones are registered at [GymAutomationRule.MIN_GEOFENCE_RADIUS_M].
     */
    val geofenceRadiusMeters get() = maxOf(radiusMeters, GymAutomationRule.MIN_GEOFENCE_RADIUS_M)
    /** Outer (departure) boundary: geofenced radius + max(50 m, 25 %). Always outside the inner fence. */
    val exitRadiusMeters get() = geofenceRadiusMeters + maxOf(50, geofenceRadiusMeters / 4)
    /** True when the chosen zone is below what geofencing reliably resolves. */
    val isBelowReliableRadius get() = radiusMeters < GymAutomationRule.MIN_GEOFENCE_RADIUS_M
    fun sameGeometry(o: GymZone) = latitude == o.latitude && longitude == o.longitude && radiusMeters == o.radiusMeters
}

/** The gym automation settings: shared actions plus up to [MAX_ZONES] locations. */
data class GymAutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val enabled: Boolean = false,
    val zones: List<GymZone> = emptyList(),
    val hrActionEnabled: Boolean = true,
    val restoreOnExit: Boolean = true,
) {
    /** Zones that can be registered (have a position). */
    val validZones get() = zones.filter { it.hasLocation }
    val hasLocation get() = zones.any { it.hasLocation }
    val canAddZone get() = zones.size < MAX_ZONES
    /** Geometry revision of every registrable zone; the reducer validates transitions against it. */
    val zoneRevisions: Map<String, Long> get() = validZones.associate { it.id to it.revision }
    fun zone(id: String?) = zones.firstOrNull { it.id == id }

    companion object {
        const val SCHEMA = 2
        const val DEFAULT_RADIUS_M = 160
        const val MIN_RADIUS_M = 20
        const val MAX_RADIUS_M = 300
        const val RADIUS_STEP_M = 20
        const val MIN_GEOFENCE_RADIUS_M = 100
        /** 2 geofences per zone; well under Android's 100-per-app limit. */
        const val MAX_ZONES = 10

        /** Number of intermediate slider stops between MIN and MAX. */
        const val RADIUS_SLIDER_STEPS = (MAX_RADIUS_M - MIN_RADIUS_M) / RADIUS_STEP_M - 1

        /** Clamps to [MIN_RADIUS_M, MAX_RADIUS_M] and rounds to the nearest 20 m step (ties round up). */
        fun normalizeRadius(meters: Int): Int {
            val c = meters.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
            val steps = (c - MIN_RADIUS_M + RADIUS_STEP_M / 2) / RADIUS_STEP_M
            return (MIN_RADIUS_M + steps * RADIUS_STEP_M).coerceAtMost(MAX_RADIUS_M)
        }
        fun normalizeRadius(meters: Float): Int =
            normalizeRadius(if (meters.isNaN()) DEFAULT_RADIUS_M else Math.round(meters))
    }
}

object GymAutomationRepository {
    const val PREFS = "gym_automation"
    private const val KEY_RULE = "rule"
    private const val KEY_JOURNAL = "journal"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rule(ctx: Context): GymAutomationRule = decodeRule(prefs(ctx).getString(KEY_RULE, null))

    /** Saves [rule] after normalizing it against what is stored. */
    @Synchronized
    fun saveRule(ctx: Context, rule: GymAutomationRule): GymAutomationRule {
        val saved = normalizeForSave(rule(ctx), rule)
        prefs(ctx).edit().putString(KEY_RULE, encodeRule(saved)).commit()
        return saved
    }

    /**
     * Pure save rules: radius snapped to the 20 m grid, at most [GymAutomationRule.MAX_ZONES]
     * zones, a zone's revision bumped only when its geometry changed (new zones continue
     * after any revision the id had before), the rule revision bumped on any change, and the
     * rule cannot be enabled without at least one located zone.
     */
    fun normalizeForSave(old: GymAutomationRule, next: GymAutomationRule): GymAutomationRule {
        val zones = next.zones.take(GymAutomationRule.MAX_ZONES).distinctBy { it.id }.map { z0 ->
            val z = z0.copy(radiusMeters = GymAutomationRule.normalizeRadius(z0.radiusMeters), name = z0.name.trim().take(60))
            val prev = old.zone(z.id)
            when {
                prev == null -> z
                prev.sameGeometry(z) -> z.copy(revision = prev.revision)
                else -> z.copy(revision = maxOf(prev.revision, z.revision) + 1)
            }
        }
        val candidate = next.copy(zones = zones, enabled = next.enabled && zones.any { it.hasLocation })
        return if (old.copy(revision = candidate.revision) != candidate) candidate.copy(revision = old.revision + 1)
            else candidate.copy(revision = old.revision)
    }

    fun journal(ctx: Context): Journal = migrateJournal(decodeJournal(prefs(ctx).getString(KEY_JOURNAL, null)), rule(ctx))
    fun saveJournal(ctx: Context, j: Journal) { prefs(ctx).edit().putString(KEY_JOURNAL, encodeJournal(j)).commit() }

    /** A schema-1 journal inside the single zone keeps that zone (migrated with the same id/revision). */
    fun migrateJournal(j: Journal, rule: GymAutomationRule): Journal =
        if (j.legacyInside && j.insideZones.isEmpty() && j.presence == Presence.INSIDE) {
            val z = rule.zones.firstOrNull { it.id == rule.id } ?: rule.validZones.firstOrNull()
            j.copy(insideZones = z?.let { mapOf(it.id to it.revision) }.orEmpty(), legacyInside = false)
        } else j.copy(legacyInside = false)

    fun encodeRule(r: GymAutomationRule): String = JSONObject().apply {
        put("schema", GymAutomationRule.SCHEMA); put("id", r.id); put("revision", r.revision)
        put("enabled", r.enabled); put("hr", r.hrActionEnabled); put("restore", r.restoreOnExit)
        put("zones", JSONArray().apply {
            r.zones.forEach { z ->
                put(JSONObject().apply {
                    put("id", z.id); put("name", z.name); put("radius", z.radiusMeters); put("rev", z.revision)
                    z.latitude?.let { put("lat", it) }; z.longitude?.let { put("lon", it) }
                })
            }
        })
    }.toString()

    fun decodeRule(raw: String?): GymAutomationRule = runCatching {
        val o = JSONObject(raw!!)
        val id = o.getString("id")
        val revision = o.optLong("revision", 1)
        val zones = if (o.has("zones")) {
            val a = o.getJSONArray("zones")
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val z = a.getJSONObject(i)
                    GymZone(
                        id = z.getString("id"), name = z.optString("name"),
                        latitude = if (z.has("lat")) z.getDouble("lat") else null,
                        longitude = if (z.has("lon")) z.getDouble("lon") else null,
                        radiusMeters = GymAutomationRule.normalizeRadius(z.optInt("radius", GymAutomationRule.DEFAULT_RADIUS_M)),
                        revision = z.optLong("rev", 1),
                    )
                }.getOrNull()
            }.distinctBy { it.id }.take(GymAutomationRule.MAX_ZONES)
        } else if (o.has("lat") && o.has("lon")) {
            // Schema 1 (single zone): keep the rule id and revision so already-registered
            // geofences ("gym:<id>:<rev>:in/out") and an ongoing visit stay valid. The
            // 100–1000 m presets migrate onto the 20–300 m / 20 m grid.
            listOf(GymZone(
                id = id, name = o.optString("label"),
                latitude = o.getDouble("lat"), longitude = o.getDouble("lon"),
                radiusMeters = GymAutomationRule.normalizeRadius(o.optInt("radius", GymAutomationRule.DEFAULT_RADIUS_M)),
                revision = revision,
            ))
        } else emptyList()
        GymAutomationRule(
            id = id, revision = revision, zones = zones,
            enabled = o.optBoolean("enabled") && zones.any { it.hasLocation },
            hrActionEnabled = o.optBoolean("hr", true), restoreOnExit = o.optBoolean("restore", true),
        )
    }.getOrDefault(GymAutomationRule())

    fun encodeJournal(j: Journal): String = JSONObject().apply {
        put("rev", j.ruleRevision); put("presence", j.presence.name)
        j.visitId?.let { put("visit", it) }; j.bootId?.let { put("boot", it) }
        j.departureDeadline?.let { put("deadline", it) }
        put("gymWasOn", j.gymWasOn); put("ownsGym", j.ownsGym); put("ownsHr", j.ownsHr)
        put("applied", j.applied); put("gymSup", j.gymSuppressed); put("hrSup", j.hrSuppressed)
        put("locUnavailable", j.locationUnavailable)
        put("inside", JSONObject().apply { j.insideZones.forEach { (k, v) -> put(k, v) } })
    }.toString()

    fun decodeJournal(raw: String?): Journal = runCatching {
        val o = JSONObject(raw!!)
        val inside = o.optJSONObject("inside")
        Journal(
            ruleRevision = o.optLong("rev"),
            presence = runCatching { Presence.valueOf(o.getString("presence")) }.getOrDefault(Presence.UNKNOWN),
            visitId = o.optString("visit").ifEmpty { null }, bootId = o.optString("boot").ifEmpty { null },
            departureDeadline = if (o.has("deadline")) o.getLong("deadline") else null,
            gymWasOn = o.optBoolean("gymWasOn"), ownsGym = o.optBoolean("ownsGym"), ownsHr = o.optBoolean("ownsHr"),
            applied = o.optBoolean("applied"), gymSuppressed = o.optBoolean("gymSup"),
            hrSuppressed = o.optBoolean("hrSup"), locationUnavailable = o.optBoolean("locUnavailable"),
            insideZones = inside?.keys()?.asSequence()?.associateWith { inside.getLong(it) }.orEmpty(),
            legacyInside = inside == null,
        )
    }.getOrDefault(Journal())
}
