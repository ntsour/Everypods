/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** One saved gym (V1). Stored only on this device; never logged with coordinates. */
data class GymAutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val enabled: Boolean = false,
    val label: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = DEFAULT_RADIUS_M,
    val hrActionEnabled: Boolean = true,
    val restoreOnExit: Boolean = true,
) {
    val hasLocation get() = latitude != null && longitude != null &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    /** Outer (departure) boundary: radius + max(50 m, 25 %). */
    val exitRadiusMeters get() = radiusMeters + maxOf(50, radiusMeters / 4)

    companion object {
        const val SCHEMA = 1
        const val DEFAULT_RADIUS_M = 150
        const val MIN_RADIUS_M = 100
        const val MAX_RADIUS_M = 1000
        val RADIUS_PRESETS = listOf(100, 150, 250, 500, 1000)
    }
}

object GymAutomationRepository {
    const val PREFS = "gym_automation"
    private const val KEY_RULE = "rule"
    private const val KEY_JOURNAL = "journal"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rule(ctx: Context): GymAutomationRule = decodeRule(prefs(ctx).getString(KEY_RULE, null))

    /** Saves [rule], bumping its revision whenever anything that affects registration changes. */
    fun saveRule(ctx: Context, rule: GymAutomationRule): GymAutomationRule {
        val old = rule(ctx)
        val bumped = if (old.copy(revision = rule.revision) != rule) rule.copy(revision = old.revision + 1) else rule
        val clamped = bumped.copy(radiusMeters = bumped.radiusMeters.coerceIn(
            GymAutomationRule.MIN_RADIUS_M, GymAutomationRule.MAX_RADIUS_M),
            enabled = bumped.enabled && bumped.hasLocation)
        prefs(ctx).edit().putString(KEY_RULE, encodeRule(clamped)).commit()
        return clamped
    }

    fun journal(ctx: Context): Journal = decodeJournal(prefs(ctx).getString(KEY_JOURNAL, null))
    fun saveJournal(ctx: Context, j: Journal) { prefs(ctx).edit().putString(KEY_JOURNAL, encodeJournal(j)).commit() }

    fun encodeRule(r: GymAutomationRule): String = JSONObject().apply {
        put("schema", GymAutomationRule.SCHEMA); put("id", r.id); put("revision", r.revision)
        put("enabled", r.enabled); put("label", r.label)
        r.latitude?.let { put("lat", it) }; r.longitude?.let { put("lon", it) }
        put("radius", r.radiusMeters); put("hr", r.hrActionEnabled); put("restore", r.restoreOnExit)
    }.toString()

    fun decodeRule(raw: String?): GymAutomationRule = runCatching {
        val o = JSONObject(raw!!)
        GymAutomationRule(
            id = o.getString("id"), revision = o.optLong("revision", 1), enabled = o.optBoolean("enabled"),
            label = o.optString("label"),
            latitude = if (o.has("lat")) o.getDouble("lat") else null,
            longitude = if (o.has("lon")) o.getDouble("lon") else null,
            radiusMeters = o.optInt("radius", GymAutomationRule.DEFAULT_RADIUS_M),
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
    }.toString()

    fun decodeJournal(raw: String?): Journal = runCatching {
        val o = JSONObject(raw!!)
        Journal(
            ruleRevision = o.optLong("rev"),
            presence = runCatching { Presence.valueOf(o.getString("presence")) }.getOrDefault(Presence.UNKNOWN),
            visitId = o.optString("visit").ifEmpty { null }, bootId = o.optString("boot").ifEmpty { null },
            departureDeadline = if (o.has("deadline")) o.getLong("deadline") else null,
            gymWasOn = o.optBoolean("gymWasOn"), ownsGym = o.optBoolean("ownsGym"), ownsHr = o.optBoolean("ownsHr"),
            applied = o.optBoolean("applied"), gymSuppressed = o.optBoolean("gymSup"),
            hrSuppressed = o.optBoolean("hrSup"), locationUnavailable = o.optBoolean("locUnavailable"),
        )
    }.getOrDefault(Journal())
}
