/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod

/** Entry point that asked heart rate to start or stop. Persisted as the session owner. */
enum class HeartRateSource {
    CASE_SHORTCUT, HEART_RATE_SCREEN, NOTIFICATION, GYM_MODE, AUTOMATION, SYSTEM;

    /** A deliberate user stop: suppresses the gym rule's heart rate for the current visit. */
    val isUserStop get() = this == CASE_SHORTCUT || this == HEART_RATE_SCREEN || this == NOTIFICATION
}

/** Outcome of the shared "start heart rate like the case shortcut" step. */
sealed interface HrStartDecision {
    /** Monitoring, recovering or sharing already: left running, never toggled off. */
    data object AlreadyRunning : HrStartDecision
    /** No explicit sharing selection: nothing is started and no method is picked. */
    data object NoneSelected : HrStartDecision
    /** Runtime permissions the selected method needs; background callers only report them. */
    data class NeedsPermission(val method: HeartRateSharingMethod, val permissions: List<String>) : HrStartDecision
    /** Selected method is not available in this build (e.g. DIRCON in a release build). */
    data class Unavailable(val method: HeartRateSharingMethod) : HrStartDecision
    data class Start(val method: HeartRateSharingMethod) : HrStartDecision
}

/**
 * Single, pure decision shared by the case-art shortcut and the Gym automation.
 * It never reads [HeartRateSharingPrefs.method], whose TICKR fallback must not
 * decide what starts; only the explicit [selected] method counts.
 */
object HeartRateStartPlanner {
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun requiredPermissions(method: HeartRateSharingMethod, sdkInt: Int): List<String> = buildList {
        add("android.permission.POST_NOTIFICATIONS")
        add("android.permission.BLUETOOTH_CONNECT")
        if (method == HeartRateSharingMethod.BLUETOOTH) add("android.permission.BLUETOOTH_ADVERTISE")
        if (method == HeartRateSharingMethod.DIRCON && sdkInt >= 37) add(LOCAL_NETWORK)
    }

    fun decide(
        active: Boolean,
        selected: HeartRateSharingMethod?,
        dirconEnabled: Boolean,
        sdkInt: Int,
        isGranted: (String) -> Boolean,
    ): HrStartDecision {
        if (active) return HrStartDecision.AlreadyRunning
        val method = selected ?: return HrStartDecision.NoneSelected
        if (method == HeartRateSharingMethod.DIRCON && !dirconEnabled) return HrStartDecision.Unavailable(method)
        val missing = requiredPermissions(method, sdkInt).filterNot(isGranted)
        return if (missing.isEmpty()) HrStartDecision.Start(method) else HrStartDecision.NeedsPermission(method, missing)
    }
}
