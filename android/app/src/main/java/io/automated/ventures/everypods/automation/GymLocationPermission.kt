package io.automated.ventures.everypods.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Single source of truth for the optional Gym location automation permission.
 *
 * Android 11+ never offers "Allow all the time" in the in-app runtime dialog. The flow is:
 *  1. explain, then request precise (fine + coarse) location via the runtime dialog
 *     ("While using the app" / "Only this time" / "Don't allow");
 *  2. once granted, explain again and request ACCESS_BACKGROUND_LOCATION on its own. The
 *     system shows no dialog: it opens the app's Location permission page, where the user picks
 *     "Allow all the time". If the system ignores the request (asked before), open App info.
 * Grant state is re-read on resume; nothing is ever assumed.
 */
object GymLocationPermission {
    enum class Step { NEED_PRECISE, NEED_BACKGROUND, GRANTED }

    /** What the next tap (or automatic continuation) should do. */
    enum class Action {
        /** Show the step-1 explanation, then the runtime dialog. */
        EXPLAIN_FOREGROUND,
        /** Foreground was permanently denied: the dialog can't be shown again, open App info. */
        SETTINGS_FOREGROUND,
        /** Show the step-2 explanation ("choose Allow all the time"), then request background. */
        EXPLAIN_BACKGROUND,
        /** Background was already requested this session and not granted: open App info with guidance. */
        SETTINGS_BACKGROUND,
        NONE,
    }

    val FOREGROUND = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    const val BACKGROUND = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    fun step(fineGranted: Boolean, backgroundGranted: Boolean): Step = when {
        !fineGranted -> Step.NEED_PRECISE
        !backgroundGranted -> Step.NEED_BACKGROUND
        else -> Step.GRANTED
    }

    fun step(ctx: Context): Step = step(
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        GymGeofenceRegistrar.hasPermissions(ctx),
    )

    /** Pure decision: granted state plus what was already tried this session -> next action. */
    fun nextAction(
        step: Step,
        foregroundAskedThisSession: Boolean,
        foregroundCanShowRationale: Boolean,
        backgroundAskedThisSession: Boolean,
    ): Action = when (step) {
        Step.GRANTED -> Action.NONE
        Step.NEED_PRECISE ->
            if (shouldOpenSettingsForForeground(foregroundAskedThisSession, foregroundCanShowRationale)) Action.SETTINGS_FOREGROUND
            else Action.EXPLAIN_FOREGROUND
        Step.NEED_BACKGROUND -> if (backgroundAskedThisSession) Action.SETTINGS_BACKGROUND else Action.EXPLAIN_BACKGROUND
    }

    /** After the foreground dialog returns: continue straight to step 2 when precise was granted. */
    fun continueAfterForeground(fineGranted: Boolean, backgroundGranted: Boolean): Action =
        if (fineGranted && !backgroundGranted) Action.EXPLAIN_BACKGROUND else Action.NONE

    /** Foreground denied permanently: the dialog can't be shown again, so go to App info. */
    fun shouldOpenSettingsForForeground(askedThisSession: Boolean, canShowRationale: Boolean) =
        askedThisSession && !canShowRationale

    /** A pending "enable automation" completes only once "Allow all the time" is granted. */
    fun shouldCompleteEnable(pendingEnable: Boolean, step: Step, hasLocation: Boolean) =
        pendingEnable && hasLocation && step == Step.GRANTED

    fun appSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The system's own localized label for "Allow all the time". */
    fun allowAllTheTimeLabel(ctx: Context): String? =
        runCatching { ctx.packageManager.backgroundPermissionOptionLabel.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
}
