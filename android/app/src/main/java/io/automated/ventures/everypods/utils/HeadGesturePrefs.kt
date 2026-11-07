package io.automated.ventures.everypods.utils

import android.content.SharedPreferences
import io.automated.ventures.everypods.data.*

object HeadGesturePrefs {
    private const val VERSION = "head_gesture_schema_version"
    const val STOP_ANNOUNCEMENTS = "head_gesture_shake_stops_announcements"
    const val NOTICE = "head_gesture_mute_migration_notice"
    const val GYM_TUNING = "head_gesture_separate_gym_tuning"
    fun actionKey(gesture: HeadGesture, gym: Boolean) = "head_gesture_${if (gym) "gym" else "normal"}_${gesture.name.lowercase()}_action"
    fun callKey(action: HeadGestureCallAction) = "head_gesture_call_${action.name.lowercase()}"
    private fun tuningKey(gesture: HeadGesture, gym: Boolean, field: String) =
        "head_gesture_${if (gym) "gym" else "normal"}_${gesture.name.lowercase()}_$field"

    private fun defaultTuning(gym: Boolean) = GestureTuning(
        amplitude = if (gym) 800 else 600,
        repetitions = 2, cooldownMs = 2000, maxPhaseGapMs = 800,
        maxDurationMs = 2000, axisDominancePercent = 300,
    )

    /** Called at process startup before other settings are written. Restored or existing settings win. */
    @Synchronized fun initializeFreshInstall(prefs: SharedPreferences) {
        if (prefs.all.isNotEmpty()) return
        val edit = prefs.edit()
            .putBoolean("head_gestures_enabled", true)
            .putBoolean(STOP_ANNOUNCEMENTS, true)
            .putBoolean(GYM_TUNING, true)
            .putBoolean("head_gesture_gym_tuning_initialized", true)
            .putBoolean("use_alternate_head_tracking_packets", true)
            .putInt(VERSION, 3)
        HeadGesture.entries.forEach { gesture ->
            listOf(false, true).forEach { gym -> writeTuning(edit, gesture, gym, defaultTuning(gym)) }
        }
        edit.putString(actionKey(HeadGesture.NOD, false), StemAction.NEXT_TRACK.name)
            .putString(actionKey(HeadGesture.SHAKE, false), StemAction.PLAY_PAUSE.name)
            .putString(actionKey(HeadGesture.NOD, true), StemAction.GYM_TIMER_START_STOP.name)
            .putString(actionKey(HeadGesture.SHAKE, true), StemAction.PLAY_PAUSE.name)
            .putString(callKey(HeadGestureCallAction.ANSWER), HeadGesture.NOD.name)
            .putString(callKey(HeadGestureCallAction.DECLINE), HeadGesture.SHAKE.name)
            .putString(callKey(HeadGestureCallAction.MUTE_TOGGLE), HeadGesture.NOD.name)
            .putString(callKey(HeadGestureCallAction.HANG_UP), "NONE")
            .commit()
    }

    @Synchronized fun migrate(prefs: SharedPreferences) {
        val version = prefs.getInt(VERSION, 0)
        if (version >= 3) return
        if (version >= 1) {
            // Retire the manual stream override and retain main's announcement preference.
            val edit = prefs.edit().remove("head_tracking_enabled")
            migrateAnnouncementPreference(prefs, edit)
            edit.putInt(VERSION, 3).commit()
            return
        }
        val legacy = prefs.contains("head_gestures_enabled") || prefs.contains("head_gestures_answer_call") || prefs.contains("head_gestures_mute_call")
        val incoming = legacy && prefs.getBoolean("head_gestures_answer_call", true)
        val mute = legacy && prefs.getBoolean("head_gestures_mute_call", true)
        val edit = prefs.edit()
        HeadGesture.entries.forEach { gesture ->
            listOf(false, true).forEach { gym ->
                if (!prefs.contains(actionKey(gesture, gym))) edit.putString(actionKey(gesture, gym), "NONE")
                writeTuning(edit, gesture, gym, readTuning(prefs, gesture, gym))
            }
        }
        val defaults = mapOf(
            HeadGestureCallAction.ANSWER to if (incoming) HeadGesture.NOD else null,
            HeadGestureCallAction.DECLINE to if (incoming) HeadGesture.SHAKE else null,
            HeadGestureCallAction.MUTE_TOGGLE to if (mute) HeadGesture.SHAKE else null,
            HeadGestureCallAction.HANG_UP to null,
        )
        defaults.forEach { (action, gesture) ->
            if (!prefs.contains(callKey(action))) edit.putString(callKey(action), gesture?.name ?: "NONE")
        }
        migrateAnnouncementPreference(prefs, edit)
        edit.putBoolean(NOTICE, mute)
            .remove("head_tracking_enabled").putInt(VERSION, 3).commit()
    }

    private fun migrateAnnouncementPreference(prefs: SharedPreferences, edit: SharedPreferences.Editor) {
        if (!prefs.contains(STOP_ANNOUNCEMENTS) && prefs.contains("head_gestures_stop_announcement")) {
            edit.putBoolean(STOP_ANNOUNCEMENTS, prefs.getBoolean("head_gestures_stop_announcement", true))
        }
    }

    fun read(prefs: SharedPreferences): HeadGestureSettings {
        migrate(prefs)
        fun actions(gym: Boolean) = HeadGesture.entries.associateWith { StemAction.fromString(prefs.getString(actionKey(it, gym), "NONE") ?: "NONE") }
        return HeadGestureSettings(
            enabled = prefs.getBoolean("head_gestures_enabled", false),
            shakeStopsAnnouncements = prefs.getBoolean(STOP_ANNOUNCEMENTS, true),
            normal = actions(false), gym = actions(true),
            calls = HeadGestureCallAction.entries.associateWith { action ->
                HeadGesture.entries.find { it.name == prefs.getString(callKey(action), "NONE") }
            },
            tuning = HeadGesture.entries.associateWith { readTuning(prefs, it, false) },
            gymTuning = HeadGesture.entries.associateWith { readTuning(prefs, it, true) },
            separateGymTuning = prefs.getBoolean(GYM_TUNING, false),
        )
    }

    fun setShakeStopsAnnouncements(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(STOP_ANNOUNCEMENTS, enabled).apply()
    }

    fun setAction(prefs: SharedPreferences, gesture: HeadGesture, gym: Boolean, action: StemAction?) {
        prefs.edit().putString(actionKey(gesture, gym), action?.name ?: "NONE").apply()
    }

    fun setCall(prefs: SharedPreferences, action: HeadGestureCallAction, gesture: HeadGesture?) {
        val settings = read(prefs)
        val edit = prefs.edit()
        if (gesture != null) HeadGestureCallAction.entries.filter {
            it != action && (it in HeadGestureSettings.ringingActions) == (action in HeadGestureSettings.ringingActions) && settings.calls[it] == gesture
        }.forEach { edit.putString(callKey(it), "NONE") }
        edit.putString(callKey(action), gesture?.name ?: "NONE").apply()
    }

    private fun readTuning(prefs: SharedPreferences, gesture: HeadGesture, gym: Boolean) = GestureTuning(
        prefs.getInt(tuningKey(gesture, gym, "amplitude"), 800),
        prefs.getInt(tuningKey(gesture, gym, "repetitions"), 2),
        prefs.getInt(tuningKey(gesture, gym, "cooldown"), 2000),
        prefs.getInt(tuningKey(gesture, gym, "max_phase_gap"), 1500),
        prefs.getInt(tuningKey(gesture, gym, "max_duration"), 6000),
        prefs.getInt(tuningKey(gesture, gym, "axis_dominance"), 150),
    ).validated()

    private fun writeTuning(edit: SharedPreferences.Editor, gesture: HeadGesture, gym: Boolean, tuning: GestureTuning) {
        val valid = tuning.validated()
        edit.putInt(tuningKey(gesture, gym, "amplitude"), valid.amplitude)
            .putInt(tuningKey(gesture, gym, "repetitions"), valid.repetitions)
            .putInt(tuningKey(gesture, gym, "cooldown"), valid.cooldownMs)
            .putInt(tuningKey(gesture, gym, "max_phase_gap"), valid.maxPhaseGapMs)
            .putInt(tuningKey(gesture, gym, "max_duration"), valid.maxDurationMs)
            .putInt(tuningKey(gesture, gym, "axis_dominance"), valid.axisDominancePercent)
    }

    fun setTuning(prefs: SharedPreferences, gesture: HeadGesture, gym: Boolean, tuning: GestureTuning) {
        val edit = prefs.edit()
        writeTuning(edit, gesture, gym, tuning)
        edit.apply()
    }

    fun setGymTuning(prefs: SharedPreferences, enabled: Boolean) {
        val edit = prefs.edit()
        if (enabled && !prefs.getBoolean("head_gesture_gym_tuning_initialized", false)) {
            HeadGesture.entries.forEach { writeTuning(edit, it, true, readTuning(prefs, it, false)) }
            edit.putBoolean("head_gesture_gym_tuning_initialized", true)
        }
        edit.putBoolean(GYM_TUNING, enabled).apply()
    }

    fun resetTuning(prefs: SharedPreferences, gym: Boolean) {
        val edit = prefs.edit()
        HeadGesture.entries.forEach { writeTuning(edit, it, gym, defaultTuning(gym)) }
        edit.apply()
    }
}
