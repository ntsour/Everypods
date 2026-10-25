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

/** Registers the inner DWELL and outer EXIT geofences for the current rule revision. */
object GymGeofenceRegistrar {
    const val LOITER_MS = 60_000

    fun hasPermissions(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun innerId(rule: GymAutomationRule) = "gym:${rule.id}:${rule.revision}:in"
    fun outerId(rule: GymAutomationRule) = "gym:${rule.id}:${rule.revision}:out"

    /** Parses a request id back to (revision, isInner) for the current rule, else null. */
    fun parse(rule: GymAutomationRule, requestId: String): Pair<Long, Boolean>? {
        val parts = requestId.split(":")
        if (parts.size != 4 || parts[0] != "gym" || parts[1] != rule.id) return null
        val revision = parts[2].toLongOrNull() ?: return null
        return when (parts[3]) { "in" -> revision to true; "out" -> revision to false; else -> null }
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 4711, Intent(ctx, GymGeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Idempotent: replaces any previous registration with the current revision, or removes it. */
    @SuppressLint("MissingPermission")
    fun sync(ctx: Context) {
        val rule = GymAutomationRepository.rule(ctx)
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            if (!rule.enabled || !rule.hasLocation) {
                Log.i(GymAutomationCoordinator.TAG, "geofences removed (rule off)")
                return@addOnCompleteListener
            }
            if (!hasPermissions(ctx)) {
                Log.w(GymAutomationCoordinator.TAG, "geofences not registered: location permission missing")
                return@addOnCompleteListener
            }
            val inner = Geofence.Builder().setRequestId(innerId(rule))
                .setCircularRegion(rule.latitude!!, rule.longitude!!, rule.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(LOITER_MS).build()
            val outer = Geofence.Builder().setRequestId(outerId(rule))
                .setCircularRegion(rule.latitude, rule.longitude, rule.exitRadiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).build()
            val request = GeofencingRequest.Builder()
                // Initial DWELL supports enabling while already inside; no initial EXIT.
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(listOf(inner, outer)).build()
            runCatching {
                client.addGeofences(request, pi)
                    .addOnSuccessListener { Log.i(GymAutomationCoordinator.TAG, "geofences registered rev=${rule.revision}") }
                    .addOnFailureListener { e ->
                        Log.w(GymAutomationCoordinator.TAG, "geofence registration failed: ${e.javaClass.simpleName}")
                        GymAutomationCoordinator.dispatch(ctx, Event.LocationUnavailable)
                    }
            }.onFailure { Log.w(GymAutomationCoordinator.TAG, "geofence registration error: ${it.javaClass.simpleName}") }
        }
    }
}
