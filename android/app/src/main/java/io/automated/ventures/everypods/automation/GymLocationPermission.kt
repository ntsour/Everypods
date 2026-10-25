package io.automated.ventures.everypods.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Single source of truth for the optional Gym location automation permission.
 * Android requires two steps: foreground (precise) location via the runtime dialog,
 * then "Allow all the time" from the app's system settings page (Android 11+).
 * Both the Gym automation editor and the Permissions screen read state from here.
 */
object GymLocationPermission {
    enum class Step { NEED_PRECISE, NEED_BACKGROUND, GRANTED }

    val FOREGROUND = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun step(fineGranted: Boolean, backgroundGranted: Boolean): Step = when {
        !fineGranted -> Step.NEED_PRECISE
        !backgroundGranted -> Step.NEED_BACKGROUND
        else -> Step.GRANTED
    }

    fun step(ctx: Context): Step = step(
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        GymGeofenceRegistrar.hasPermissions(ctx),
    )

    /** Foreground denied permanently: the dialog can't be shown again, so go to App info. */
    fun shouldOpenSettingsForForeground(askedThisSession: Boolean, canShowRationale: Boolean) =
        askedThisSession && !canShowRationale

    fun appSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
