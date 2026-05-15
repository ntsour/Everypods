/*
    ProPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 ProPods contributors

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

package io.nikos.propods.utils

import android.content.Context
import android.util.Log
import io.nikos.propods.data.Battery
import io.nikos.propods.data.BatteryComponent
import io.nikos.propods.data.BatteryStatus

/**
 * Watches battery state updates and fires TTS announcements on a tiered
 * escalation schedule:
 *
 *  1. First alert when level crosses below the user threshold (default 40 %).
 *  2. Every 5 % decrease below threshold        (e.g. 35 %, 30 %, 25 %…)
 *  3. Every 2 % decrease once below 10 %        (e.g. 9 %, 7 %, 5 %…)
 *  4. Once at or below 2 %, repeat every 10 min (time-based reminder).
 *
 * State resets when the component recovers above threshold + RESET_MARGIN.
 * Charging/disconnect updates clear any pending in-ear alert.
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
        var armed: Boolean = false,                  // true once we've crossed below threshold
        var pendingLevel: Int? = null                // low level seen while buds were out of ear / in case
    )

    private val state = mutableMapOf<Int, ComponentState>()

    private fun stateFor(component: Int) =
        state.getOrPut(component) { ComponentState() }

    fun resetState() {
        state.clear()
    }

    fun checkAndMaybeAlert(
        ctx: Context,
        batteries: List<Battery>,
        anyBudInEar: Boolean,
        forceSpeakIfLow: Boolean = false
    ) {
        if (!SmartFeaturesPrefs.batteryAlertsEnabled(ctx)) return
        val threshold = SmartFeaturesPrefs.batteryAlertThreshold(ctx)

        for (b in batteries) {
            if (b.component !in setOf(
                    BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE
                )
            ) continue

            val s = stateFor(b.component)

            // Buds report fresh low levels while entering the case; queue those until
            // they are worn again. The case often reports only while open/charging, so
            // do not suppress case alerts just because it is charging.
            if ((b.status == BatteryStatus.CHARGING ||
                    b.status == BatteryStatus.OPTIMIZED_CHARGING ||
                    b.status == BatteryStatus.DISCONNECTED) &&
                b.component != BatteryComponent.CASE
            ) {
                s.pendingLevel = null
                continue
            }
            if (b.level <= 0) continue

            // Rearm: recovered well above threshold — start fresh
            if (b.level > threshold + RESET_MARGIN) {
                state.remove(b.component)
                continue
            }

            val level = b.level
            val now = System.currentTimeMillis()
            val shouldSpeak = shouldSpeakNow(s, level, now, threshold, forceSpeakIfLow)

            if (s.pendingLevel != null && (anyBudInEar || forceSpeakIfLow)) {
                val pendingLevel = s.pendingLevel ?: level
                Log.d(TAG, "${b.component} low battery pending alert ($pendingLevel%)")
                speak(ctx, Battery(b.component, pendingLevel, b.status))
                markAlerted(s, pendingLevel, now)
                s.pendingLevel = null
                continue
            }

            if (shouldSpeak) {
                if (anyBudInEar || forceSpeakIfLow) {
                    Log.d(TAG, "${b.component} low battery alert at $level%")
                    speak(ctx, b)
                    markAlerted(s, level, now)
                } else {
                    Log.d(TAG, "${b.component} low battery queued until buds are in ear ($level%)")
                    s.pendingLevel = minOf(s.pendingLevel ?: level, level)
                    s.armed = true
                }
            }
        }
    }

    private fun shouldSpeakNow(
        s: ComponentState,
        level: Int,
        now: Long,
        threshold: Int,
        forceSpeakIfLow: Boolean
    ): Boolean {
        if (forceSpeakIfLow) return level <= threshold
        if (level <= REPEAT_LEVEL) {
            return now - s.lastAlertedAt >= REPEAT_INTERVAL_MS
        }
        if (!s.armed) return level <= threshold
        val step = if (level < CRITICAL_LEVEL) STEP_CRITICAL else STEP_NORMAL
        return level <= s.lastAlertedLevel - step
    }

    private fun markAlerted(s: ComponentState, level: Int, now: Long) {
        s.lastAlertedAt = now
        s.lastAlertedLevel = level
        s.armed = true
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
        val engine = AnnouncementPrefs.ttsEngine(ctx)
        val languageForSystemTts = AnnouncementPrefs.languageForText(ctx, text)
        val elevenLabsLanguageCode = AnnouncementPrefs.elevenLabsLanguageCode(ctx)
        if (engine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS) {
            val apiKey = AnnouncementPrefs.elevenLabsApiKey(ctx)
            val voiceId = AnnouncementPrefs.elevenLabsVoiceId(ctx)
            if (apiKey.isNotBlank()) {
                ElevenLabsEngine.speak(
                    context = ctx,
                    text = text,
                    apiKey = apiKey,
                    voiceId = voiceId,
                    languageCode = elevenLabsLanguageCode,
                    onFallback = { reason ->
                        Log.w(TAG, "ElevenLabs failed for battery alert ($reason), falling back to system TTS")
                        TtsEngine.speak(ctx, text, languageForSystemTts)
                    }
                )
                return
            }
            Log.w(TAG, "ElevenLabs selected but no API key — using system TTS for battery alert")
        }
        TtsEngine.speak(ctx, text, languageForSystemTts)
    }
}
