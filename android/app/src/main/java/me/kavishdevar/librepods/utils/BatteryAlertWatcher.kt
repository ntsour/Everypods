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
import android.util.Log
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus

/**
 * Watches battery state updates and fires TTS announcements on a tiered
 * escalation schedule:
 *
 *  1. First alert when level crosses below the user threshold (default 40 %).
 *  2. Every 5 % decrease below threshold        (e.g. 35 %, 30 %, 25 %…)
 *  3. Every 2 % decrease once below 10 %        (e.g. 9 %, 7 %, 5 %…)
 *  4. Once at or below 2 %, repeat every 10 min (time-based reminder).
 *
 * State resets when the component starts charging or recovers above
 * threshold + RESET_MARGIN.
 */
object BatteryAlertWatcher {

    private const val TAG = "BatteryAlert"
    private const val RESET_MARGIN = 10         // rearm only after recovering this many % above threshold

    private const val STEP_NORMAL = 5           // alert every 5 % below threshold
    private const val STEP_CRITICAL = 2         // alert every 2 % once below this level
    private const val CRITICAL_LEVEL = 10
    private const val REPEAT_LEVEL = 2          // repeat time-based at or below this level
    private const val REPEAT_INTERVAL_MS = 10 * 60_000L  // 10 minutes

    // Per-component state (keyed by BatteryComponent Int constant)
    private data class ComponentState(
        var lastAlertedLevel: Int = Int.MAX_VALUE,   // level at which we last spoke
        var lastAlertedAt: Long = 0L,                // timestamp of last alert
        var armed: Boolean = false                   // true once we've crossed below threshold
    )

    private val state = mutableMapOf<Int, ComponentState>()

    private fun stateFor(component: Int) =
        state.getOrPut(component) { ComponentState() }

    fun checkAndMaybeAlert(ctx: Context, batteries: List<Battery>) {
        if (!SmartFeaturesPrefs.batteryAlertsEnabled(ctx)) return
        val threshold = SmartFeaturesPrefs.batteryAlertThreshold(ctx)

        for (b in batteries) {
            if (b.component !in setOf(
                    BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE
                )
            ) continue

            val s = stateFor(b.component)

            // Reset on charging or disconnect
            if (b.status == BatteryStatus.CHARGING ||
                b.status == BatteryStatus.OPTIMIZED_CHARGING ||
                b.status == BatteryStatus.DISCONNECTED
            ) {
                state.remove(b.component as Int)
                continue
            }
            if (b.level <= 0) continue

            // Rearm: recovered well above threshold — start fresh
            if (b.level > threshold + RESET_MARGIN) {
                state.remove(b.component as Int)
                continue
            }

            val level = b.level
            val now = System.currentTimeMillis()

            // ── Tier 4: repeat at or below 2 % every 10 min ──────────────────
            if (level <= REPEAT_LEVEL) {
                if (now - s.lastAlertedAt >= REPEAT_INTERVAL_MS) {
                    Log.d(TAG, "${b.component} critically low ($level%) — repeating every 10 min")
                    speak(ctx, b)
                    s.lastAlertedAt = now
                    s.lastAlertedLevel = level
                    s.armed = true
                }
                continue
            }

            // ── Arm: first crossing below threshold ───────────────────────────
            if (!s.armed && level <= threshold) {
                Log.d(TAG, "${b.component} crossed threshold at $level%")
                speak(ctx, b)
                s.lastAlertedAt = now
                s.lastAlertedLevel = level
                s.armed = true
                continue
            }

            if (!s.armed) continue  // still above threshold

            // ── Tier 2/3: step-based escalation ──────────────────────────────
            val step = if (level < CRITICAL_LEVEL) STEP_CRITICAL else STEP_NORMAL
            // Alert when we've dropped at least `step` % below the last alerted level
            if (level <= s.lastAlertedLevel - step) {
                Log.d(TAG, "${b.component} stepped down to $level% (step=$step%)")
                speak(ctx, b)
                s.lastAlertedAt = now
                s.lastAlertedLevel = level
            }
        }
    }

    private fun speak(ctx: Context, b: Battery) {
        val componentKey = when (b.component) {
            BatteryComponent.LEFT -> "left"
            BatteryComponent.RIGHT -> "right"
            BatteryComponent.CASE -> "case"
            else -> "battery"
        }
        val language = AnnouncementPrefs.resolvedLanguage(ctx)
        val text = AnnouncementPrefs.batteryAlert(language, componentKey, b.level)
        TtsEngine.speak(ctx, text)
    }
}
