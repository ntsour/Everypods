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
    data class Reading(val presence: Presence, val inside: Map<String, Long> = emptyMap(),
                       val outside: Set<String> = emptySet())
    private val unknown = Reading(Presence.UNKNOWN)

    @SuppressLint("MissingPermission") // Caller checks GymGeofenceRegistrar.hasPermissions.
    fun request(ctx: Context, rule: GymAutomationRule, done: (Reading, Long?) -> Unit) {
        val zones = rule.validZones
        if (zones.isEmpty()) { done(unknown, null); return }
        val once = AtomicBoolean(false)
        fun finish(reading: Reading, observedAt: Long? = null) {
            if (once.compareAndSet(false, true)) done(reading, observedAt)
        }
        val cancel = CancellationTokenSource()
        val main = Handler(Looper.getMainLooper())
        // Our own cap in case Play services never completes the task.
        main.postDelayed({
            if (!once.get()) GymAutomationDiagnostics.record(ctx, "fix timeout")
            cancel.cancel(); finish(unknown)
        }, PresenceEvidence.FIX_TIMEOUT_MS)
        GymAutomationDiagnostics.record(ctx, "location request maxAgeMs=${PresenceEvidence.MAX_FIX_AGE_MS} timeoutMs=${PresenceEvidence.FIX_TIMEOUT_MS} priority=high ${GymAutomationDiagnostics.environment(ctx)}")
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(PresenceEvidence.FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(PresenceEvidence.MAX_FIX_AGE_MS)
            .build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(request, cancel.token)
                .addOnSuccessListener { loc ->
                    val classified = runCatching { classify(ctx, loc, zones, "fix") }.getOrElse {
                        GymAutomationDiagnostics.record(ctx, "fix classification_failed error=${it.javaClass.simpleName}")
                        unknown
                    }
                    finish(classified, loc?.elapsedRealtimeNanos?.div(1_000_000))
                }
                .addOnFailureListener { e ->
                    Log.w(GymAutomationCoordinator.TAG, "presence fix failed: ${e.javaClass.simpleName}")
                    GymAutomationDiagnostics.record(ctx, "fix failed error=${e.javaClass.simpleName}")
                    finish(unknown)
                }
        }.onFailure {
            Log.w(GymAutomationCoordinator.TAG, "presence fix error: ${it.javaClass.simpleName}")
            GymAutomationDiagnostics.record(ctx, "fix error=${it.javaClass.simpleName}")
            finish(unknown)
        }
    }

    fun classify(ctx: Context, loc: Location?, zones: List<GymZone>, source: String): Reading {
        if (loc == null) {
            GymAutomationDiagnostics.record(ctx, "location source=$source result=missing")
            return unknown
        }
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        GymAutomationDiagnostics.record(ctx, "location source=$source ageMs=$ageMs accuracyM=${if (loc.hasAccuracy()) loc.accuracy.toInt() else -1} provider=${when (loc.provider) { "gps", "network", "fused", "passive" -> loc.provider; else -> "other" }} mock=${loc.isMock}")
        if (!loc.hasAccuracy()) return unknown
        val perZone = zones.mapIndexed { index, z ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.latitude!!, z.longitude!!, out)
            val result = PresenceEvidence.classify(out[0].toDouble(), loc.accuracy.toDouble(), ageMs,
                z.geofenceRadiusMeters, z.exitRadiusMeters)
            GymAutomationDiagnostics.record(ctx, "location source=$source zone=$index rev=${z.revision} distanceM=${(out[0] / 10).toInt() * 10} innerM=${z.geofenceRadiusMeters} outerM=${z.exitRadiusMeters} classification=$result")
            z to result
        }
        val (presence, inside) = PresenceEvidence.combine(perZone.map { (zone, result) ->
            Triple(zone.id, zone.revision, result)
        })
        val outside = perZone.filter { (_, result) -> result == Presence.OUTSIDE }.map { (zone, _) -> zone.id }.toSet()
        return Reading(presence, inside, outside)
    }
}
