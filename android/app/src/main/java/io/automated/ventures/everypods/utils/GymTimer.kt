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

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

object GymTimer {

    private const val TAG = "GymTimer"

    enum class State { IDLE, RUNNING, PAUSED }
    enum class Mode { COUNTDOWN, STOPWATCH, HIIT }
    enum class Phase { WORK, REST }
    enum class CountdownMilestone { QUARTER, HALF, THREE_QUARTERS }

    /** Semantic events consumed by the single timer-announcement coordinator. */
    sealed interface AnnouncementEvent {
        data class Started(val mode: Mode) : AnnouncementEvent
        data class Resumed(val mode: Mode, val elapsedMs: Long) : AnnouncementEvent
        data class Paused(val mode: Mode, val elapsedMs: Long, val remainingMs: Long) : AnnouncementEvent
        data class Reset(val mode: Mode) : AnnouncementEvent
        data class CountdownCue(
            val milestone: CountdownMilestone?,
            val finalSecond: Int?
        ) : AnnouncementEvent
        data class StopwatchInterval(val elapsedMs: Long) : AnnouncementEvent
        data class HiitPhaseStarted(val phase: Phase, val round: Int) : AnnouncementEvent
        data class HiitCountdown(val seconds: Int) : AnnouncementEvent
        data class LapRecorded(val lap: Lap) : AnnouncementEvent
        data class Completed(val mode: Mode) : AnnouncementEvent
    }

    data class Lap(val number: Int, val elapsedMs: Long, val splitMs: Long)

    // Use dedicated HandlerThread for timer to run in background
    private val timerThread = HandlerThread("GymTimerThread").apply { start() }
    private val handler = Handler(timerThread.looper)
    private var tickRunnable: Runnable? = null

    private var state = State.IDLE
    private var startTimeMs = 0L
    private var elapsedBeforePause = 0L
    private val laps = mutableListOf<Lap>()
    
    // Announcement tracking
    private val announcedCountdownMilestones = mutableSetOf<CountdownMilestone>()
    private var lastCountdownSecond: Int? = null
    private var lastHiitPhase: Phase? = null
    private var lastHiitCountdownSecond: Int? = null
    private var lastStopwatchMinute = 0L

    // Mode-specific config
    private var mode = Mode.COUNTDOWN
    private var countdownDurationMs = 60_000L // default 1 min
    private var hiitWorkMs = 40_000L          // default 40s
    private var hiitRestMs = 20_000L          // default 20s
    private var hiitRounds = 8                // default 8 rounds

    private val listeners = mutableListOf<() -> Unit>()
    private val announcementListeners = mutableListOf<(AnnouncementEvent) -> Unit>()

    fun state(): State = state
    fun mode(): Mode = mode
    fun elapsedMs(): Long = when (state) {
        State.IDLE -> 0L
        State.RUNNING -> elapsedBeforePause + (SystemClock.elapsedRealtime() - startTimeMs)
        State.PAUSED -> elapsedBeforePause
    }
    fun laps(): List<Lap> = laps.toList()

    // Config getters/setters
    fun getCountdownDurationMs(): Long = countdownDurationMs
    fun setCountdownDurationMs(ms: Long) {
        countdownDurationMs = ms
        notifyListeners()  // Notify UI of duration change
    }

    fun getHiitWorkMs(): Long = hiitWorkMs
    fun setHiitWorkMs(ms: Long) {
        hiitWorkMs = ms
        notifyListeners()  // Notify UI of work duration change
    }
    fun getHiitRestMs(): Long = hiitRestMs
    fun setHiitRestMs(ms: Long) {
        hiitRestMs = ms
        notifyListeners()  // Notify UI of rest duration change
    }
    fun getHiitRounds(): Int = hiitRounds
    fun setHiitRounds(rounds: Int) {
        hiitRounds = rounds
        notifyListeners()  // Notify UI of rounds change
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        if (state != State.IDLE) reset()
        notifyListeners()
    }

    /** Remaining ms for countdown mode */
    fun countdownRemainingMs(): Long {
        val remaining = countdownDurationMs - elapsedMs()
        return if (remaining > 0) remaining else 0L
    }

    /** Current HIIT phase info: (phase, round, remainingInPhaseMs) */
    fun hiitPhaseInfo(): Triple<Phase, Int, Long> {
        val elapsed = elapsedMs()
        val cycleMs = hiitWorkMs + hiitRestMs
        val completedCycles = elapsed / cycleMs
        val withinCycle = elapsed % cycleMs
        val round = (completedCycles + 1).toInt().coerceAtMost(hiitRounds)

        return if (round > hiitRounds) {
            Triple(Phase.REST, hiitRounds, 0L)
        } else if (withinCycle < hiitWorkMs) {
            Triple(Phase.WORK, round, hiitWorkMs - withinCycle)
        } else {
            Triple(Phase.REST, round, cycleMs - withinCycle)
        }
    }

    /** Whether HIIT is complete (all rounds done) */
    fun hiitComplete(): Boolean {
        val totalMs = hiitRounds * (hiitWorkMs + hiitRestMs)
        return elapsedMs() >= totalMs
    }

    fun start() {
        if (state == State.RUNNING) return
        val wasPaused = state == State.PAUSED
        startTimeMs = SystemClock.elapsedRealtime()
        state = State.RUNNING
        startTicking()
        Log.d(TAG, "Started mode=$mode")
        if (wasPaused) {
            emitAnnouncement(AnnouncementEvent.Resumed(mode, elapsedBeforePause))
        } else {
            resetAnnouncementTracking()
            emitAnnouncement(AnnouncementEvent.Started(mode))
        }
        notifyListeners()
    }

    fun pause() {
        pause(announce = true)
    }

    private fun pause(announce: Boolean) {
        if (state != State.RUNNING) return
        elapsedBeforePause += SystemClock.elapsedRealtime() - startTimeMs
        state = State.PAUSED
        stopTicking()
        Log.d(TAG, "Paused at ${elapsedBeforePause}ms")
        if (announce) {
            emitAnnouncement(
                AnnouncementEvent.Paused(
                    mode = mode,
                    elapsedMs = elapsedBeforePause,
                    remainingMs = when (mode) {
                        Mode.COUNTDOWN -> countdownRemainingMs()
                        Mode.HIIT -> hiitPhaseInfo().third
                        Mode.STOPWATCH -> 0L
                    }
                )
            )
        }
        notifyListeners()
    }

    fun startStop() {
        when (state) {
            State.IDLE, State.PAUSED -> start()
            State.RUNNING -> pause()
        }
    }

    fun lap() {
        if (state != State.RUNNING || mode != Mode.STOPWATCH) return
        val total = elapsedMs()
        val split = total - (laps.lastOrNull()?.elapsedMs ?: 0L)
        val lap = Lap(laps.size + 1, total, split)
        laps.add(lap)
        Log.d(TAG, "Lap ${lap.number}: ${lap.splitMs}ms (total ${lap.elapsedMs}ms)")
        emitAnnouncement(AnnouncementEvent.LapRecorded(lap))
        notifyListeners()
    }

    fun reset(announce: Boolean = true) {
        stopTicking()
        state = State.IDLE
        startTimeMs = 0L
        elapsedBeforePause = 0L
        laps.clear()
        resetAnnouncementTracking()
        Log.d(TAG, "Reset")
        if (announce) emitAnnouncement(AnnouncementEvent.Reset(mode))
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun addAnnouncementListener(listener: (AnnouncementEvent) -> Unit) {
        announcementListeners.add(listener)
    }

    fun removeAnnouncementListener(listener: (AnnouncementEvent) -> Unit) {
        announcementListeners.remove(listener)
    }

    private fun startTicking() {
        stopTicking()
        val runnable = object : Runnable {
            override fun run() {
                processTimerTick()
                notifyListeners()
                if (state == State.RUNNING) {
                    handler.postDelayed(this, 100)
                }
            }
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, 100)
    }

    private fun processTimerTick() {
        when (mode) {
            Mode.COUNTDOWN -> {
                if (countdownRemainingMs() <= 0) {
                    complete()
                } else {
                    emitCountdownCues()
                }
            }
            Mode.HIIT -> {
                if (hiitComplete()) {
                    complete()
                } else {
                    emitHiitCues()
                }
            }
            Mode.STOPWATCH -> emitStopwatchCue()
        }
    }

    private fun complete() {
        pause(announce = false)
        emitAnnouncement(AnnouncementEvent.Completed(mode))
    }

    private fun emitCountdownCues() {
        val elapsed = elapsedMs()
        val crossed = listOf(
            CountdownMilestone.QUARTER to countdownDurationMs / 4,
            CountdownMilestone.HALF to countdownDurationMs / 2,
            CountdownMilestone.THREE_QUARTERS to (countdownDurationMs * 3) / 4,
        ).filter { (milestone, threshold) ->
            elapsed >= threshold && announcedCountdownMilestones.add(milestone)
        }.map { it.first }

        val finalSecond = kotlin.math.ceil(countdownRemainingMs() / 1_000.0)
            .toInt().takeIf { it in 1..10 && it != lastCountdownSecond }
        if (finalSecond != null) lastCountdownSecond = finalSecond

        crossed.dropLast(1).forEach { emitAnnouncement(AnnouncementEvent.CountdownCue(it, null)) }
        if (crossed.isNotEmpty() || finalSecond != null) {
            emitAnnouncement(AnnouncementEvent.CountdownCue(crossed.lastOrNull(), finalSecond))
        }
    }

    private fun emitStopwatchCue() {
        val minute = elapsedMs() / 60_000L
        if (minute >= 1 && minute > lastStopwatchMinute) {
            lastStopwatchMinute = minute
            emitAnnouncement(AnnouncementEvent.StopwatchInterval(elapsedMs()))
        }
    }

    private fun emitHiitCues() {
        val (phase, round, remainingMs) = hiitPhaseInfo()
        if (phase != lastHiitPhase) {
            lastHiitPhase = phase
            lastHiitCountdownSecond = null
            emitAnnouncement(AnnouncementEvent.HiitPhaseStarted(phase, round))
        }
        val second = kotlin.math.ceil(remainingMs / 1_000.0).toInt()
        if ((second == 10 || second in 1..3) && second != lastHiitCountdownSecond) {
            lastHiitCountdownSecond = second
            emitAnnouncement(AnnouncementEvent.HiitCountdown(second))
        }
    }

    private fun resetAnnouncementTracking() {
        announcedCountdownMilestones.clear()
        lastCountdownSecond = null
        lastHiitPhase = null
        lastHiitCountdownSecond = null
        lastStopwatchMinute = 0L
    }

    private fun emitAnnouncement(event: AnnouncementEvent) {
        Handler(Looper.getMainLooper()).post {
            announcementListeners.toList().forEach { it.invoke(event) }
        }
    }

    private fun stopTicking() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun notifyListeners() {
        // Post to main looper to ensure UI updates happen on main thread
        Looper.getMainLooper().let { mainLooper ->
            Handler(mainLooper).post {
                listeners.toList().forEach { it.invoke() }
            }
        }
    }
}
