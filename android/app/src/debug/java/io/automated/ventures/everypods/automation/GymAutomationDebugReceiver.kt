/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence

/**
 * Debug-build simulator for QA. It drives the same receiver/coordinator path as a real
 * geofence callback, so it proves wiring and decisions, not real-world detection.
 *
 *   adb shell am broadcast -a io.automated.ventures.everypods.debug.GYM_AUTOMATION --es cmd arrive
 *   cmd = arrive | exit | grace | unavailable | resume | status | configure
 *   arrive/exit/configure/remove accept --ei zone <index> (default 0)
 *   configure extras: --ef lat 40.4 --ef lon -3.7 --ei radius 160 --es name Gym --ez enabled true --ez hr true --ez restore true
 *     (configure with a zone index equal to the zone count adds a zone)
 *   remove: deletes the zone at --ei zone
 */
class GymAutomationDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rule = GymAutomationRepository.rule(context)
        val index = intent.getIntExtra("zone", 0)
        val zone = rule.zones.getOrNull(index)
        when (intent.getStringExtra("cmd")) {
            "arrive" -> zone?.let { GymGeofenceReceiver.handle(context, Geofence.GEOFENCE_TRANSITION_DWELL, listOf(GymGeofenceRegistrar.innerId(it))) }
            "exit" -> zone?.let { GymGeofenceReceiver.handle(context, Geofence.GEOFENCE_TRANSITION_EXIT, listOf(GymGeofenceRegistrar.outerId(it))) }
            "remove" -> zone?.let {
                GymAutomationRepository.saveRule(context, rule.copy(zones = rule.zones - it))
                GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
                GymGeofenceRegistrar.sync(context)
            }
            "grace" -> GymAutomationCoordinator.dispatch(context, Event.GraceCheck)
            "unavailable" -> GymAutomationCoordinator.dispatch(context, Event.LocationUnavailable)
            "resume" -> GymAutomationCoordinator.dispatch(context, Event.ResumeVisit)
            "configure" -> {
                val base = zone ?: GymZone()
                val edited = base.copy(
                    name = intent.getStringExtra("name") ?: base.name,
                    latitude = if (intent.hasExtra("lat")) intent.getFloatExtra("lat", 0f).toDouble() else base.latitude,
                    longitude = if (intent.hasExtra("lon")) intent.getFloatExtra("lon", 0f).toDouble() else base.longitude,
                    radiusMeters = intent.getIntExtra("radius", base.radiusMeters),
                )
                val zones = if (zone != null) rule.zones.map { if (it.id == zone.id) edited else it } else rule.zones + edited
                val saved = GymAutomationRepository.saveRule(context, rule.copy(
                    zones = zones,
                    enabled = intent.getBooleanExtra("enabled", rule.enabled),
                    hrActionEnabled = intent.getBooleanExtra("hr", rule.hrActionEnabled),
                    restoreOnExit = intent.getBooleanExtra("restore", rule.restoreOnExit),
                ))
                GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
                GymGeofenceRegistrar.sync(context)
                Log.i(GymAutomationCoordinator.TAG, "debug configure rev=${saved.revision} enabled=${saved.enabled}")
            }
        }
        Log.i(GymAutomationCoordinator.TAG, "debug status rule=${rule.enabled}/${rule.revision} zones=${rule.zones.size} " +
            "journal=${GymAutomationRepository.journal(context).copy(visitId = null)} status=${GymAutomationCoordinator.status.value}")
    }
}
