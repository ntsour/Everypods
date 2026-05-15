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

package io.nikos.andropods.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Single-shot media-pause timer. When started, the deadline is persisted in
 * SharedPreferences so the active timer survives a screen rotation / config
 * change in the settings UI. (Process death isn't covered — for a hard
 * sleep timer use AlarmManager instead, but for the immediate "I'm dozing
 * off and want music to stop" use case this is fine.)
 */
object SleepTimer {

    private const val TAG = "SleepTimer"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTask: Runnable? = null
    private var listeners = mutableListOf<() -> Unit>()

    /** Start a timer for [durationMs] from now. Cancels any existing timer. */
    fun start(ctx: Context, durationMs: Long) {
        cancel(ctx)
        val endAt = System.currentTimeMillis() + durationMs
        SmartFeaturesPrefs.setSleepTimerEndAt(ctx, endAt)
        val task = Runnable {
            Log.d(TAG, "Sleep timer expired — pausing media")
            MediaController.sendPause(force = true)
            SmartFeaturesPrefs.setSleepTimerEndAt(ctx, 0L)
            pendingTask = null
            notifyListeners()
        }
        pendingTask = task
        handler.postDelayed(task, durationMs)
        Log.d(TAG, "Sleep timer started, ${durationMs}ms remaining")
        notifyListeners()
    }

    fun cancel(ctx: Context) {
        pendingTask?.let { handler.removeCallbacks(it) }
        pendingTask = null
        SmartFeaturesPrefs.setSleepTimerEndAt(ctx, 0L)
        Log.d(TAG, "Sleep timer cancelled")
        notifyListeners()
    }

    fun isRunning(): Boolean = pendingTask != null

    /** Remaining ms until the timer fires, or 0 if not running. */
    fun remainingMs(ctx: Context): Long {
        val endAt = SmartFeaturesPrefs.sleepTimerEndAt(ctx)
        if (endAt == 0L) return 0L
        val remaining = endAt - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.invoke() }
    }
}
