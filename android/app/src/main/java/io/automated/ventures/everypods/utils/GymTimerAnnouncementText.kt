/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package io.automated.ventures.everypods.utils

/** Converts timer events into concise, eyes-free speech. */
object GymTimerAnnouncementText {

    fun forEvent(
        event: GymTimer.AnnouncementEvent,
        stopwatchIntervalMinutes: Int
    ): String? = when (event) {
        is GymTimer.AnnouncementEvent.Started -> when (event.mode) {
            GymTimer.Mode.COUNTDOWN ->
                "Countdown started. ${duration(GymTimer.getCountdownDurationMs())}."
            GymTimer.Mode.STOPWATCH -> "Stopwatch started."
            GymTimer.Mode.HIIT ->
                "HIIT started. ${GymTimer.getHiitRounds()} rounds. Work ${duration(GymTimer.getHiitWorkMs())}. Rest ${duration(GymTimer.getHiitRestMs())}."
        }
        is GymTimer.AnnouncementEvent.Resumed -> when (event.mode) {
            GymTimer.Mode.COUNTDOWN -> "Countdown resumed. ${duration(GymTimer.countdownRemainingMs())} remaining."
            GymTimer.Mode.STOPWATCH -> "Stopwatch resumed. ${duration(event.elapsedMs)} elapsed."
            GymTimer.Mode.HIIT -> "HIIT resumed. ${duration(GymTimer.hiitPhaseInfo().third)} remaining."
        }
        is GymTimer.AnnouncementEvent.Paused -> when (event.mode) {
            GymTimer.Mode.COUNTDOWN -> "Countdown paused. ${duration(event.remainingMs)} remaining."
            GymTimer.Mode.STOPWATCH -> "Stopwatch paused. ${duration(event.elapsedMs)} elapsed."
            GymTimer.Mode.HIIT -> "HIIT paused. ${duration(event.remainingMs)} remaining."
        }
        is GymTimer.AnnouncementEvent.Reset -> when (event.mode) {
            GymTimer.Mode.COUNTDOWN -> "Countdown reset."
            GymTimer.Mode.STOPWATCH -> "Stopwatch reset."
            GymTimer.Mode.HIIT -> "HIIT reset."
        }
        is GymTimer.AnnouncementEvent.CountdownCue -> {
            val milestone = event.milestone?.let {
                when (it) {
                    GymTimer.CountdownMilestone.QUARTER -> "One quarter complete"
                    GymTimer.CountdownMilestone.HALF -> "Halfway"
                    GymTimer.CountdownMilestone.THREE_QUARTERS -> "Three quarters complete"
                }
            }
            listOfNotNull(milestone, event.finalSecond?.toString()).joinToString(". ").takeIf { it.isNotBlank() }
        }
        is GymTimer.AnnouncementEvent.StopwatchInterval -> {
            val minutes = event.elapsedMs / 60_000L
            if (stopwatchIntervalMinutes > 0 && minutes % stopwatchIntervalMinutes == 0L) {
                "${duration(event.elapsedMs)} elapsed."
            } else null
        }
        is GymTimer.AnnouncementEvent.HiitPhaseStarted ->
            "Round ${event.round}. ${event.phase.name.lowercase()}."
        is GymTimer.AnnouncementEvent.HiitCountdown -> event.seconds.toString()
        is GymTimer.AnnouncementEvent.LapRecorded ->
            "Lap ${event.lap.number}. ${duration(event.lap.splitMs)}. Total ${duration(event.lap.elapsedMs)}."
        is GymTimer.AnnouncementEvent.Completed -> when (event.mode) {
            GymTimer.Mode.COUNTDOWN -> "Countdown complete."
            GymTimer.Mode.HIIT -> "HIIT complete."
            GymTimer.Mode.STOPWATCH -> null
        }
    }

    internal fun duration(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (minutes > 0) add("$minutes minute${if (minutes == 1L) "" else "s"}")
            if (seconds > 0 || minutes == 0L) add("$seconds second${if (seconds == 1L) "" else "s"}")
        }.joinToString(" ")
    }
}
