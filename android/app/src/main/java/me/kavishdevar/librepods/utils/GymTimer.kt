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
    private var lastAnnouncedSecond = -1L
    private var lastAnnouncedPhase: Phase? = null

    // Mode-specific config
    private var mode = Mode.COUNTDOWN
    private var countdownDurationMs = 60_000L // default 1 min
    private var hiitWorkMs = 40_000L          // default 40s
    private var hiitRestMs = 20_000L          // default 20s
    private var hiitRounds = 8                // default 8 rounds

    private val listeners = mutableListOf<() -> Unit>()

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
        if (state != State.IDLE) reset()
        mode = newMode
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
        startTimeMs = SystemClock.elapsedRealtime()
        state = State.RUNNING
        startTicking()
        Log.d(TAG, "Started mode=$mode")
        notifyListeners()
    }

    fun pause() {
        if (state != State.RUNNING) return
        elapsedBeforePause += SystemClock.elapsedRealtime() - startTimeMs
        state = State.PAUSED
        stopTicking()
        Log.d(TAG, "Paused at ${elapsedBeforePause}ms")
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
        notifyListeners()
    }

    fun reset() {
        stopTicking()
        state = State.IDLE
        startTimeMs = 0L
        elapsedBeforePause = 0L
        laps.clear()
        lastAnnouncedSecond = -1L
        lastAnnouncedPhase = null
        Log.d(TAG, "Reset")
        notifyListeners()
    }

    /** Get pending announcement texts for current timer state.
     *  Returns list of strings to announce, or empty list if nothing to announce.
     *  Clears the announcement after returning it.
     */
    fun pollAnnouncements(): List<String> {
        if (state != State.RUNNING) return emptyList()

        val announcements = mutableListOf<String>()

        when (mode) {
            Mode.COUNTDOWN -> {
                val remainingSec = countdownRemainingMs() / 1000
                
                // Final countdown: 10-1
                if (remainingSec in 1..10 && remainingSec != lastAnnouncedSecond) {
                    announcements.add(remainingSec.toString())
                    lastAnnouncedSecond = remainingSec
                }
                // Every 30s intervals (only if > 10s remaining)
                else if (remainingSec > 10 && remainingSec % 30 == 0L && remainingSec != lastAnnouncedSecond) {
                    announcements.add("$remainingSec seconds remaining")
                    lastAnnouncedSecond = remainingSec
                }
            }
            Mode.HIIT -> {
                val (phase, _, remainingInPhase) = hiitPhaseInfo()
                val remainingSec = remainingInPhase / 1000
                
                // Announce phase transitions
                if (phase != lastAnnouncedPhase) {
                    announcements.add(phase.name)
                    lastAnnouncedPhase = phase
                    lastAnnouncedSecond = -1L  // reset to allow periodic announcements
                }
                // Final countdown in last 10s of phase
                else if (remainingSec in 1..10 && remainingSec != lastAnnouncedSecond) {
                    announcements.add(remainingSec.toString())
                    lastAnnouncedSecond = remainingSec
                }
                // Every 30s in phase (only if > 10s remaining)
                else if (remainingSec > 10 && remainingSec % 30 == 0L && remainingSec != lastAnnouncedSecond) {
                    announcements.add("$remainingSec seconds in ${phase.name.lowercase()}")
                    lastAnnouncedSecond = remainingSec
                }
            }
            Mode.STOPWATCH -> { /* no announcements */ }
        }

        return announcements
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun startTicking() {
        stopTicking()
        val runnable = object : Runnable {
            override fun run() {
                checkAutoTransitions()
                notifyListeners()
                if (state == State.RUNNING) {
                    handler.postDelayed(this, 100)
                }
            }
        }
        tickRunnable = runnable
        handler.postDelayed(runnable, 100)
    }

    private fun checkAutoTransitions() {
        when (mode) {
            Mode.COUNTDOWN -> {
                if (countdownRemainingMs() <= 0) {
                    pause()
                }
            }
            Mode.HIIT -> {
                if (hiitComplete()) {
                    pause()
                }
            }
            Mode.STOPWATCH -> { /* no auto transitions */ }
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
