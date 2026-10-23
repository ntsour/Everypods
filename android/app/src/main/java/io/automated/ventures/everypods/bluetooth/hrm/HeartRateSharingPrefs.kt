/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

import android.content.Context
import io.automated.ventures.everypods.BuildConfig

enum class HeartRateSharingMethod { TICKR, BLUETOOTH, DIRCON }

object HeartRateSharingPrefs {
    const val METHOD = "hr_sharing_method"
    const val GYM_AUTO = "hr_sharing_gym_auto"
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** No explicit selection means monitoring only, rather than an implicit broadcast. */
    fun configuredMethod(context: Context): HeartRateSharingMethod? {
        val saved = prefs(context).getString(METHOD, null) ?: return null
        val method = runCatching { HeartRateSharingMethod.valueOf(saved) }.getOrNull() ?: return null
        return method.takeUnless { it == HeartRateSharingMethod.DIRCON && !BuildConfig.DIRCON_ENABLED }
    }

    fun clearMethod(context: Context) = prefs(context).edit().remove(METHOD).putBoolean(GYM_AUTO, false).apply()
    fun method(context: Context) = runCatching {
        HeartRateSharingMethod.valueOf(prefs(context).getString(METHOD, "TICKR")!!)
    }.getOrDefault(HeartRateSharingMethod.TICKR).let {
        if (it == HeartRateSharingMethod.DIRCON && !BuildConfig.DIRCON_ENABLED) HeartRateSharingMethod.TICKR else it
    }
    fun setMethod(context: Context, method: HeartRateSharingMethod) =
        prefs(context).edit().putString(METHOD, method.name).apply()
    fun gymAuto(context: Context) = prefs(context).getBoolean(GYM_AUTO, false)
    fun setGymAuto(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(GYM_AUTO, enabled).apply()
}

/** Session intent is deliberately memory-only; process restart must not start a broadcast. */
class HeartRateSharingIntent {
    var method: HeartRateSharingMethod? = null
        private set
    var gymOwned = false
        private set
    var suppressedForGym = false
        private set
    fun start(method: HeartRateSharingMethod, gym: Boolean) {
        this.method = method
        gymOwned = gym
    }
    fun stop(manual: Boolean) {
        if (manual) suppressedForGym = true
        method = null
        gymOwned = false
    }
    fun gymChanged(enabled: Boolean) {
        if (!enabled) suppressedForGym = false
    }
}
