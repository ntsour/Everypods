/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package io.automated.ventures.everypods.utils

/** Copy and formatting shared by the lock-screen Gym Timer notification. */
object GymTimerNotificationText {
    fun timerName(mode: GymTimer.Mode): String = when (mode) {
        GymTimer.Mode.COUNTDOWN -> "Countdown"
        GymTimer.Mode.STOPWATCH -> "Stopwatch"
        GymTimer.Mode.HIIT -> "HIIT"
    }

    fun runningText(
        mode: GymTimer.Mode,
        phase: GymTimer.Phase? = null,
        round: Int? = null,
        rounds: Int? = null
    ): String = when (mode) {
        GymTimer.Mode.COUNTDOWN -> "Countdown running"
        GymTimer.Mode.STOPWATCH -> "Stopwatch running"
        GymTimer.Mode.HIIT -> "${if (phase == GymTimer.Phase.WORK) "Work" else "Rest"} · Round ${round ?: 1} of ${rounds ?: 1}"
    }

    fun pausedText(mode: GymTimer.Mode, frozenMs: Long): String =
        "${timerName(mode)} paused · ${formatDuration(frozenMs)}"

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }
}
