/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.automated.ventures.everypods.services.AirPodsService
import java.util.UUID

/** Non-exported receiver for Play services geofence transitions. */
class GymGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val start = SystemClock.elapsedRealtime()
        try {
            GymAutomationDiagnostics.record(context, "receiver entered kind=${if (intent.action == ACTION_GRACE_CHECK) "grace" else "geofence"} ${GymAutomationDiagnostics.environment(context)}")
            if (intent.action == ACTION_GRACE_CHECK) {
                GymAutomationCoordinator.dispatch(context, Event.GraceCheck)
                return
            }
            val event = GeofencingEvent.fromIntent(intent)
            if (event == null) {
                GymAutomationDiagnostics.record(context, "receiver parse_failed reason=null_event")
                return
            }
            if (event.hasError()) {
                GymAutomationDiagnostics.record(context, "geofence error code=${event.errorCode}")
                if (event.errorCode == com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                    GymGeofenceRegistrar.markInvalid(context)
                    GymAutomationJobs.retryRegistration(context, 0)
                }
                GymAutomationCoordinator.dispatch(context, Event.LocationUnavailable)
                return
            }
            runCatching {
                GymPresenceFix.classify(context, event.triggeringLocation, GymAutomationRepository.rule(context).validZones, "geofence")
            }.onFailure { GymAutomationDiagnostics.record(context, "geofence location_diagnostic_failed error=${it.javaClass.simpleName}") }
            val now = SystemClock.elapsedRealtime()
            val observedAt = event.triggeringLocation?.elapsedRealtimeNanos?.div(1_000_000)
                ?.takeIf { it in 1..now }
            handle(context, event.geofenceTransition, event.triggeringGeofences?.map { it.requestId }.orEmpty(), observedAt)
        } catch (e: Exception) {
            GymAutomationDiagnostics.record(context, "receiver exception=${e.javaClass.simpleName}")
        } finally {
            GymAutomationDiagnostics.record(context, "receiver finished elapsedMs=${SystemClock.elapsedRealtime() - start}")
            pending.finish()
        }
    }

    companion object {
        const val ACTION_GRACE_CHECK = "io.automated.ventures.everypods.GYM_GRACE_CHECK"
        /** Shared by the real receiver and the debug simulator. */
        fun handle(context: Context, transition: Int, requestIds: List<String>, observedAt: Long? = null) {
            val rule = GymAutomationRepository.rule(context)
            GymAutomationDiagnostics.record(context, "geofence transition=$transition count=${requestIds.size} valid=${requestIds.count { GymGeofenceRegistrar.parse(rule, it) != null }}")
            if (!rule.enabled) return
            var accepted = false
            requestIds.distinct().forEach { id ->
                val parsed = GymGeofenceRegistrar.parse(rule, id)
                val index = parsed?.let { p -> rule.validZones.indexOfFirst { it.id == p.zoneId } } ?: -1
                val current = parsed != null && rule.zoneRevisions[parsed.zoneId] == parsed.revision
                GymAutomationDiagnostics.record(context, "geofence item zone=$index current=$current transition=$transition observedAt=$observedAt")
                if (!current) return@forEach
                val input = when {
                    parsed.inner && (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_DWELL) ->
                        Event.Arrived(parsed.revision, UUID.randomUUID().toString(), parsed.zoneId, observedAt)
                    !parsed.inner && transition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                        Event.Exited(parsed.revision, parsed.zoneId, observedAt)
                    else -> return@forEach
                }
                GymAutomationCoordinator.dispatch(context, input)
                accepted = true
            }
            if (!accepted) return
            // Geofence delivery is a background FGS-start exemption; the service owns AirPods work.
            runCatching { context.startForegroundService(Intent(context, AirPodsService::class.java)); GymAutomationDiagnostics.record(context, "service start_requested source=geofence") }
                .onFailure { GymAutomationDiagnostics.record(context, "service start_failed error=${it.javaClass.simpleName}") }
        }
    }
}
