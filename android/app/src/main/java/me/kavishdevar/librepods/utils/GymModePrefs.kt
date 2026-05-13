/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

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

package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import me.kavishdevar.librepods.data.StemAction

object GymModePrefs {

    private const val PREFS_NAME = "settings"

    // Master toggle
    private const val KEY_ENABLED = "gym_mode_enabled"

    // Gym stem actions (single press is not configurable — always standard)
    private const val KEY_LEFT_DOUBLE  = "gym_left_double_press_action"
    private const val KEY_LEFT_TRIPLE  = "gym_left_triple_press_action"
    private const val KEY_LEFT_LONG    = "gym_left_long_press_action"
    private const val KEY_RIGHT_DOUBLE = "gym_right_double_press_action"
    private const val KEY_RIGHT_TRIPLE = "gym_right_triple_press_action"
    private const val KEY_RIGHT_LONG   = "gym_right_long_press_action"

    // Timer settings
    private const val KEY_VOICE_ANNOUNCEMENTS = "gym_voice_announcements_enabled"
    private const val KEY_DEFAULT_TIMER_TYPE  = "gym_default_timer_type"

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun voiceAnnouncementsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VOICE_ANNOUNCEMENTS, true)
    fun setVoiceAnnouncementsEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VOICE_ANNOUNCEMENTS, enabled).apply()

    fun defaultTimerType(ctx: Context): String = prefs(ctx).getString(KEY_DEFAULT_TIMER_TYPE, "STOPWATCH") ?: "STOPWATCH"
    fun setDefaultTimerType(ctx: Context, type: String) = prefs(ctx).edit().putString(KEY_DEFAULT_TIMER_TYPE, type).apply()

    private fun defaultGymAction(pressType: String): StemAction = when (pressType) {
        "double"  -> StemAction.GYM_TIMER_START_STOP
        "triple"  -> StemAction.GYM_TIMER_LAP
        "long"    -> StemAction.GYM_TIMER_RESET
        else      -> StemAction.PLAY_PAUSE
    }

    fun getGymAction(ctx: Context, side: String, pressType: String): StemAction {
        val key = when (side.lowercase() + "_" + pressType.lowercase()) {
            "left_double"  -> KEY_LEFT_DOUBLE
            "left_triple"  -> KEY_LEFT_TRIPLE
            "left_long"    -> KEY_LEFT_LONG
            "right_double" -> KEY_RIGHT_DOUBLE
            "right_triple" -> KEY_RIGHT_TRIPLE
            "right_long"   -> KEY_RIGHT_LONG
            else -> return defaultGymAction(pressType)
        }
        return runCatching {
            StemAction.valueOf(prefs(ctx).getString(key, defaultGymAction(pressType).name)!!)
        }.getOrDefault(defaultGymAction(pressType))
    }

    fun setGymAction(ctx: Context, side: String, pressType: String, action: StemAction) {
        val key = when (side.lowercase() + "_" + pressType.lowercase()) {
            "left_double"  -> KEY_LEFT_DOUBLE
            "left_triple"  -> KEY_LEFT_TRIPLE
            "left_long"    -> KEY_LEFT_LONG
            "right_double" -> KEY_RIGHT_DOUBLE
            "right_triple" -> KEY_RIGHT_TRIPLE
            "right_long"   -> KEY_RIGHT_LONG
            else -> return
        }
        prefs(ctx).edit().putString(key, action.name).apply()
    }
}
