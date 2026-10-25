/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One bounded current-location fix for reconnect/restart with unknown presence.
 * Never logs coordinates; the result is only a [Presence] classification.
 */
object GymPresenceFix {
    @SuppressLint("MissingPermission") // Caller checks GymGeofenceRegistrar.hasPermissions.
    fun request(ctx: Context, rule: GymAutomationRule, done: (Presence, Map<String, Long>) -> Unit) {
        val zones = rule.validZones
        if (zones.isEmpty()) { done(Presence.UNKNOWN, emptyMap()); return }
        val once = AtomicBoolean(false)
        val finish = { p: Pair<Presence, Map<String, Long>> -> if (once.compareAndSet(false, true)) done(p.first, p.second) }
        val unknown = Presence.UNKNOWN to emptyMap<String, Long>()
        val cancel = CancellationTokenSource()
        val main = Handler(Looper.getMainLooper())
        // Our own cap in case Play services never completes the task.
        main.postDelayed({ cancel.cancel(); finish(unknown) }, PresenceEvidence.FIX_TIMEOUT_MS)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(PresenceEvidence.FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(PresenceEvidence.MAX_FIX_AGE_MS)
            .build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(request, cancel.token)
                .addOnSuccessListener { loc -> finish(classify(loc, zones)) }
                .addOnFailureListener { e ->
                    Log.w(GymAutomationCoordinator.TAG, "presence fix failed: ${e.javaClass.simpleName}")
                    finish(unknown)
                }
        }.onFailure {
            Log.w(GymAutomationCoordinator.TAG, "presence fix error: ${it.javaClass.simpleName}")
            finish(unknown)
        }
    }

    private fun classify(loc: Location?, zones: List<GymZone>): Pair<Presence, Map<String, Long>> {
        if (loc == null || !loc.hasAccuracy()) return Presence.UNKNOWN to emptyMap()
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        val perZone = zones.map { z ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.latitude!!, z.longitude!!, out)
            z to PresenceEvidence.classify(out[0].toDouble(), loc.accuracy.toDouble(), ageMs,
                z.geofenceRadiusMeters, z.exitRadiusMeters)
        }
        return PresenceEvidence.combine(perZone.map { (z, p) -> Triple(z.id, z.revision, p) })
    }
}
