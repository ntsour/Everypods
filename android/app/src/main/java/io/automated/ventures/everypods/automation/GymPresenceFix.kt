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
    fun request(ctx: Context, rule: GymAutomationRule, done: (Presence) -> Unit) {
        val lat = rule.latitude
        val lon = rule.longitude
        if (lat == null || lon == null) { done(Presence.UNKNOWN); return }
        val once = AtomicBoolean(false)
        val finish = { p: Presence -> if (once.compareAndSet(false, true)) done(p) }
        val cancel = CancellationTokenSource()
        val main = Handler(Looper.getMainLooper())
        // Our own cap in case Play services never completes the task.
        main.postDelayed({ cancel.cancel(); finish(Presence.UNKNOWN) }, PresenceEvidence.FIX_TIMEOUT_MS)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(PresenceEvidence.FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(PresenceEvidence.MAX_FIX_AGE_MS)
            .build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(request, cancel.token)
                .addOnSuccessListener { loc -> finish(classify(loc, lat, lon, rule)) }
                .addOnFailureListener { e ->
                    Log.w(GymAutomationCoordinator.TAG, "presence fix failed: ${e.javaClass.simpleName}")
                    finish(Presence.UNKNOWN)
                }
        }.onFailure {
            Log.w(GymAutomationCoordinator.TAG, "presence fix error: ${it.javaClass.simpleName}")
            finish(Presence.UNKNOWN)
        }
    }

    private fun classify(loc: Location?, lat: Double, lon: Double, rule: GymAutomationRule): Presence {
        if (loc == null || !loc.hasAccuracy()) return Presence.UNKNOWN
        val out = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, out)
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        return PresenceEvidence.classify(out[0].toDouble(), loc.accuracy.toDouble(), ageMs,
            rule.radiusMeters, rule.exitRadiusMeters)
    }
}
