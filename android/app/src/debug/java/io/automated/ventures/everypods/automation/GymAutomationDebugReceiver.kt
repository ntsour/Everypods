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
 *   configure extras: --ef lat 40.4 --ef lon -3.7 --ei radius 150 --ez enabled true --ez hr true --ez restore true
 */
class GymAutomationDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rule = GymAutomationRepository.rule(context)
        when (intent.getStringExtra("cmd")) {
            "arrive" -> GymGeofenceReceiver.handle(context, Geofence.GEOFENCE_TRANSITION_DWELL, listOf(GymGeofenceRegistrar.innerId(rule)))
            "exit" -> GymGeofenceReceiver.handle(context, Geofence.GEOFENCE_TRANSITION_EXIT, listOf(GymGeofenceRegistrar.outerId(rule)))
            "grace" -> GymAutomationCoordinator.dispatch(context, Event.GraceCheck)
            "unavailable" -> GymAutomationCoordinator.dispatch(context, Event.LocationUnavailable)
            "resume" -> GymAutomationCoordinator.dispatch(context, Event.ResumeVisit)
            "configure" -> {
                val saved = GymAutomationRepository.saveRule(context, rule.copy(
                    latitude = if (intent.hasExtra("lat")) intent.getFloatExtra("lat", 0f).toDouble() else rule.latitude,
                    longitude = if (intent.hasExtra("lon")) intent.getFloatExtra("lon", 0f).toDouble() else rule.longitude,
                    radiusMeters = intent.getIntExtra("radius", rule.radiusMeters),
                    enabled = intent.getBooleanExtra("enabled", rule.enabled),
                    hrActionEnabled = intent.getBooleanExtra("hr", rule.hrActionEnabled),
                    restoreOnExit = intent.getBooleanExtra("restore", rule.restoreOnExit),
                ))
                GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
                GymGeofenceRegistrar.sync(context)
                Log.i(GymAutomationCoordinator.TAG, "debug configure rev=${saved.revision} enabled=${saved.enabled}")
            }
        }
        Log.i(GymAutomationCoordinator.TAG, "debug status rule=${rule.enabled}/${rule.revision} " +
            "journal=${GymAutomationRepository.journal(context).copy(visitId = null)} status=${GymAutomationCoordinator.status.value}")
    }
}
