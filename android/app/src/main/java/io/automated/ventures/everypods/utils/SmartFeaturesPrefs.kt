/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences keys + accessors for the "Smart features" cluster:
 * auto-resume after call, sleep timer, battery alerts.
 *
 * Each feature is independently togglable. Defaults err on the side of
 * "don't surprise the user" — most off, a couple sensible defaults on.
 */
object SmartFeaturesPrefs {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // --- Auto-resume after call ---
    const val KEY_AUTO_RESUME_AFTER_CALL = "auto_resume_after_call"
    fun autoResumeAfterCall(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RESUME_AFTER_CALL, true)

    // --- Sleep timer ---
    /** Active sleep-timer end time in ms-since-epoch, or 0 if no timer running. */
    const val KEY_SLEEP_TIMER_END_AT = "sleep_timer_end_at"
    fun sleepTimerEndAt(ctx: Context): Long = prefs(ctx).getLong(KEY_SLEEP_TIMER_END_AT, 0L)
    fun setSleepTimerEndAt(ctx: Context, endAtMs: Long) {
        prefs(ctx).edit().putLong(KEY_SLEEP_TIMER_END_AT, endAtMs).apply()
    }

    // --- Battery alerts ---
    const val KEY_BATTERY_ALERTS_ENABLED = "battery_alerts_enabled"
    const val KEY_BATTERY_ALERT_THRESHOLD = "battery_alert_threshold"
    fun batteryAlertsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BATTERY_ALERTS_ENABLED, true)
    fun batteryAlertThreshold(ctx: Context): Int =
        prefs(ctx).getInt(KEY_BATTERY_ALERT_THRESHOLD, 40)

}
