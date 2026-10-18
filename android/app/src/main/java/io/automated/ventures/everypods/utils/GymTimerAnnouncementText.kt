/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package io.automated.ventures.everypods.utils

import android.content.Context
import io.automated.ventures.everypods.R

/** Converts timer events into resource-backed, locale-aware spoken messages. */
object GymTimerAnnouncementText {
    fun forEvent(
        context: Context,
        languageTag: String,
        event: GymTimer.AnnouncementEvent,
        stopwatchIntervalMinutes: Int
    ): LocalizedSpeech? {
        val localized = AnnouncementPrefs.contextForLanguage(context, languageTag)
        val resources = localized.resources
        fun duration(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return buildList {
                if (minutes > 0) add(resources.getQuantityString(R.plurals.speech_minutes, minutes.toInt(), minutes.toInt()))
                if (seconds > 0 || minutes == 0L) {
                    add(resources.getQuantityString(R.plurals.speech_seconds, seconds.toInt(), seconds.toInt()))
                }
            }.joinToString(" ")
        }
        val text = when (event) {
            is GymTimer.AnnouncementEvent.PreparationCountdown -> event.seconds.toString()
            is GymTimer.AnnouncementEvent.HiitCountdown -> event.seconds.toString()
            is GymTimer.AnnouncementEvent.Started -> when (event.mode) {
                GymTimer.Mode.COUNTDOWN -> resources.getString(R.string.speech_timer_countdown_started, duration(GymTimer.getCountdownDurationMs()))
                GymTimer.Mode.STOPWATCH -> resources.getString(R.string.speech_timer_stopwatch_started)
                GymTimer.Mode.HIIT -> resources.getString(
                    R.string.speech_timer_hiit_started, GymTimer.getHiitRounds(),
                    duration(GymTimer.getHiitWorkMs()), duration(GymTimer.getHiitRestMs())
                )
            }
            is GymTimer.AnnouncementEvent.Resumed -> when (event.mode) {
                GymTimer.Mode.COUNTDOWN -> resources.getString(R.string.speech_timer_countdown_resumed, duration(GymTimer.countdownRemainingMs()))
                GymTimer.Mode.STOPWATCH -> resources.getString(R.string.speech_timer_stopwatch_resumed, duration(event.elapsedMs))
                GymTimer.Mode.HIIT -> resources.getString(R.string.speech_timer_hiit_resumed, duration(GymTimer.hiitPhaseInfo().third))
            }
            is GymTimer.AnnouncementEvent.Paused -> when (event.mode) {
                GymTimer.Mode.COUNTDOWN -> resources.getString(R.string.speech_timer_countdown_paused, duration(event.remainingMs))
                GymTimer.Mode.STOPWATCH -> resources.getString(R.string.speech_timer_stopwatch_paused, duration(event.elapsedMs))
                GymTimer.Mode.HIIT -> resources.getString(R.string.speech_timer_hiit_paused, duration(event.remainingMs))
            }
            is GymTimer.AnnouncementEvent.Reset -> when (event.mode) {
                GymTimer.Mode.COUNTDOWN -> resources.getString(R.string.speech_timer_countdown_reset)
                GymTimer.Mode.STOPWATCH -> resources.getString(R.string.speech_timer_stopwatch_reset)
                GymTimer.Mode.HIIT -> resources.getString(R.string.speech_timer_hiit_reset)
            }
            is GymTimer.AnnouncementEvent.CountdownCue -> {
                val milestone = event.milestone?.let {
                    when (it) {
                        GymTimer.CountdownMilestone.QUARTER -> resources.getString(R.string.speech_timer_quarter_complete)
                        GymTimer.CountdownMilestone.HALF -> resources.getString(R.string.speech_timer_halfway)
                        GymTimer.CountdownMilestone.THREE_QUARTERS -> resources.getString(R.string.speech_timer_three_quarters_complete)
                    }
                }
                listOfNotNull(milestone, event.finalSecond?.toString())
                    .joinToString(". ").takeIf { it.isNotBlank() }
            }
            is GymTimer.AnnouncementEvent.StopwatchInterval -> {
                val minutes = event.elapsedMs / 60_000L
                if (stopwatchIntervalMinutes > 0 && minutes % stopwatchIntervalMinutes == 0L) {
                    resources.getString(R.string.speech_timer_elapsed, duration(event.elapsedMs))
                } else null
            }
            is GymTimer.AnnouncementEvent.HiitPhaseStarted -> resources.getString(
                R.string.speech_timer_round_phase, event.round,
                resources.getString(if (event.phase == GymTimer.Phase.WORK) R.string.speech_timer_work else R.string.speech_timer_rest)
            )
            is GymTimer.AnnouncementEvent.LapRecorded -> resources.getString(
                R.string.speech_timer_lap, event.lap.number,
                duration(event.lap.splitMs), duration(event.lap.elapsedMs)
            )
            is GymTimer.AnnouncementEvent.Completed -> when (event.mode) {
                GymTimer.Mode.COUNTDOWN -> resources.getString(R.string.speech_timer_countdown_complete)
                GymTimer.Mode.HIIT -> resources.getString(R.string.speech_timer_hiit_complete)
                GymTimer.Mode.STOPWATCH -> null
            }
        }
        return text?.let { LocalizedSpeech(it, languageTag) }
    }
}
