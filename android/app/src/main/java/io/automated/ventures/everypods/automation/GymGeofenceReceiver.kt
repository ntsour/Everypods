/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.automated.ventures.everypods.services.AirPodsService
import java.util.UUID

/** Non-exported receiver for Play services geofence transitions. */
class GymGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            val event = GeofencingEvent.fromIntent(intent) ?: return
            if (event.hasError()) {
                Log.w(GymAutomationCoordinator.TAG, "geofence error code=${event.errorCode}")
                GymAutomationCoordinator.dispatch(context, Event.LocationUnavailable)
                return
            }
            handle(context, event.geofenceTransition, event.triggeringGeofences?.map { it.requestId }.orEmpty())
        } finally {
            pending.finish()
        }
    }

    companion object {
        /** Shared by the real receiver and the debug simulator. */
        fun handle(context: Context, transition: Int, requestIds: List<String>) {
            val rule = GymAutomationRepository.rule(context)
            requestIds.mapNotNull { GymGeofenceRegistrar.parse(rule, it) }.forEach { p ->
                when {
                    p.inner && transition == Geofence.GEOFENCE_TRANSITION_DWELL ->
                        GymAutomationCoordinator.dispatch(context, Event.Arrived(p.revision, UUID.randomUUID().toString(), p.zoneId))
                    !p.inner && transition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                        GymAutomationCoordinator.dispatch(context, Event.Exited(p.revision, p.zoneId))
                }
            }
            // Geofence delivery is a background FGS-start exemption; the service owns AirPods work.
            runCatching { context.startForegroundService(Intent(context, AirPodsService::class.java)) }
                .onFailure { Log.w(GymAutomationCoordinator.TAG, "service start failed: ${it.javaClass.simpleName}") }
        }
    }
}
