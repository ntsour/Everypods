/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.utils.CaseConnectionJournal
import java.io.File

/** Private rolling 48-hour event history. Callers must never include coordinates or zone names. */
object GymAutomationDiagnostics {
    @Volatile private var journal: CaseConnectionJournal? = null

    @Synchronized fun initialize(context: Context) {
        if (journal == null) journal = CaseConnectionJournal(File(context.filesDir, "gym-automation-history"))
    }

    fun record(context: Context, message: String) {
        initialize(context.applicationContext)
        Log.i(GymAutomationCoordinator.TAG, message)
        runCatching { journal?.append(System.currentTimeMillis(), SystemClock.elapsedRealtime(), message) }
            .onFailure { Log.w(GymAutomationCoordinator.TAG, "gym history write failed: ${it.javaClass.simpleName}") }
    }

    /** State only: no coordinates, device identifiers, saved names, or raw intent extras. */
    fun environment(context: Context): String = runCatching {
        val app = context.applicationContext
        val location = app.getSystemService(LocationManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        "pid=${Process.myPid()} importance=$importance interactive=${power.isInteractive} " +
            "idle=${power.isDeviceIdleMode} powerSave=${power.isPowerSaveMode} " +
            "batteryExempt=${power.isIgnoringBatteryOptimizations(app.packageName)} " +
            "location=${location.isLocationEnabled} gps=${runCatching { location.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)} " +
            "network=${runCatching { location.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)} " +
            "fine=${granted(Manifest.permission.ACCESS_FINE_LOCATION)} background=${granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}"
    }.getOrElse { "environmentUnavailable=${it.javaClass.simpleName}" }

    fun processStarted(context: Context) {
        record(context, "process start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} ${environment(context)}")
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 3)
                .forEach { record(context, "process previous_exit pid=${it.pid} reason=${it.reason} status=${it.status} timestampMs=${it.timestamp} importance=${it.importance}") }
        }.onFailure { record(context, "process previous_exit unavailable=${it.javaClass.simpleName}") }
    }
}
