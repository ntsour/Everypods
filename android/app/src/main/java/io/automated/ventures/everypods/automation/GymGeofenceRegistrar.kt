/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers an inner DWELL and an outer EXIT geofence for every located zone (max 20 fences). */
object GymGeofenceRegistrar {
    const val LOITER_MS = 60_000

    fun hasPermissions(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Same shape as schema 1 ("gym:<ruleId>:<rev>:in"); the migrated zone keeps the rule id.
    fun innerId(zone: GymZone) = "gym:${zone.id}:${zone.revision}:in"
    fun outerId(zone: GymZone) = "gym:${zone.id}:${zone.revision}:out"

    data class Parsed(val zoneId: String, val revision: Long, val inner: Boolean)

    /** Parses a request id for a zone that still exists; the reducer checks the revision. */
    fun parse(rule: GymAutomationRule, requestId: String): Parsed? {
        val parts = requestId.split(":")
        if (parts.size != 4 || parts[0] != "gym" || rule.zone(parts[1]) == null) return null
        val revision = parts[2].toLongOrNull() ?: return null
        return when (parts[3]) { "in" -> Parsed(parts[1], revision, true); "out" -> Parsed(parts[1], revision, false); else -> null }
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 4711, Intent(ctx, GymGeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Idempotent: replaces any previous registration with the current zones, or removes it.
     * The rule is read after removal completes, so overlapping syncs all register the
     * latest zones rather than a stale snapshot.
     */
    @SuppressLint("MissingPermission")
    fun sync(ctx: Context) {
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            val rule = GymAutomationRepository.rule(ctx)
            if (!rule.enabled || !rule.hasLocation) {
                Log.i(GymAutomationCoordinator.TAG, "geofences removed (rule off)")
                return@addOnCompleteListener
            }
            if (!hasPermissions(ctx)) {
                Log.w(GymAutomationCoordinator.TAG, "geofences not registered: location permission missing")
                return@addOnCompleteListener
            }
            val fences = rule.validZones.flatMap { zone ->
                listOf(
                    Geofence.Builder().setRequestId(innerId(zone))
                        .setCircularRegion(zone.latitude!!, zone.longitude!!, zone.geofenceRadiusMeters.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                        .setLoiteringDelay(LOITER_MS).build(),
                    Geofence.Builder().setRequestId(outerId(zone))
                        .setCircularRegion(zone.latitude, zone.longitude, zone.exitRadiusMeters.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).build(),
                )
            }
            val request = GeofencingRequest.Builder()
                // Initial DWELL supports enabling while already inside; no initial EXIT.
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(fences).build()
            runCatching {
                client.addGeofences(request, pi)
                    .addOnSuccessListener { Log.i(GymAutomationCoordinator.TAG, "geofences registered rev=${rule.revision} zones=${rule.validZones.size}") }
                    .addOnFailureListener { e ->
                        Log.w(GymAutomationCoordinator.TAG, "geofence registration failed: ${e.javaClass.simpleName}")
                        GymAutomationCoordinator.dispatch(ctx, Event.LocationUnavailable)
                    }
            }.onFailure { Log.w(GymAutomationCoordinator.TAG, "geofence registration error: ${it.javaClass.simpleName}") }
        }
    }
}
