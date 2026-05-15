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
import android.content.SharedPreferences
import android.util.Log
import java.util.Locale
import io.nikos.andropods.services.ServiceManager

/**
 * Single source of truth for the NotificationAnnouncements feature's
 * SharedPreferences keys, defaults, and language-string lookup.
 *
 * Used by [TtsEngine], [CallAnnouncer], the announcement service, and the
 * settings screen, so all of them agree on key names and defaults.
 */
object AnnouncementPrefs {
    private const val TAG = "AnnouncementPrefs"

    const val KEY_ENABLED = "announce_enabled"
    const val KEY_ONLY_IN_EAR = "announce_only_in_ear"
    const val KEY_SKIP_DURING_CALL = "announce_skip_during_call"
    const val KEY_SKIP_DURING_MEDIA = "announce_skip_during_media"
    /** Legacy combined key, kept for migration of existing installs. */
    const val KEY_SKIP_DURING_CALL_MEDIA_LEGACY = "announce_skip_during_call_media"
    const val KEY_CONTENT_MODE = "announce_content"  // "title" or "title_body"
    const val KEY_LANGUAGE = "announce_language"     // "detect" | "auto" | BCP47 like "es", "en"
    const val KEY_APP_PREFIX = "announce_app_"        // + packageName
    const val KEY_QUIET_ENABLED = "announce_quiet_enabled"
    const val KEY_QUIET_MODE = "announce_quiet_mode"    // "manual" | "system"
    const val KEY_QUIET_START = "announce_quiet_start"  // minutes since midnight
    const val KEY_QUIET_END = "announce_quiet_end"      // minutes since midnight

    // TTS engine selection
    const val KEY_TTS_ENGINE = "announce_tts_engine"          // "system" | "elevenlabs"
    const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
    const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
    const val TTS_ENGINE_SYSTEM = "system"
    const val TTS_ENGINE_ELEVENLABS = "elevenlabs"

    const val QUIET_MODE_MANUAL = "manual"
    const val QUIET_MODE_SYSTEM = "system"

    const val CONTENT_TITLE_ONLY = "title"
    const val CONTENT_TITLE_BODY = "title_body"

    const val LANG_DETECT = "detect"
    const val LANG_SYSTEM = "auto"
    const val LANG_AUTO = LANG_SYSTEM

    // Supported announcement languages — UI shows these; matched to the
    // hardcoded call-announcement strings below.
    val SUPPORTED_LANGUAGES = listOf(
        LANG_DETECT to "Automatic detection",
        LANG_SYSTEM to "System default",
        "es" to "Español",
        "en" to "English",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "pt" to "Português",
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun onlyInEar(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ONLY_IN_EAR, false)

    fun skipDuringCall(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SKIP_DURING_CALL, true)   // default: skip during calls

    fun skipDuringMedia(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SKIP_DURING_MEDIA, false)  // default: announce even during media

    fun contentMode(ctx: Context): String =
        prefs(ctx).getString(KEY_CONTENT_MODE, CONTENT_TITLE_BODY) ?: CONTENT_TITLE_BODY

    /** Returns the user's selected language tag, or the resolved system one if not explicit. */
    fun resolvedLanguage(ctx: Context): String {
        val pref = rawLanguage(ctx)
        return when (pref) {
            LANG_DETECT, LANG_SYSTEM -> systemLanguage()
            else -> pref
        }
    }

    fun rawLanguage(ctx: Context): String =
        prefs(ctx).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    fun languageForText(ctx: Context, text: String): String {
        val pref = rawLanguage(ctx)
        return when (pref) {
            LANG_DETECT -> detectLanguage(text) ?: systemLanguage()
            LANG_SYSTEM -> systemLanguage()
            else -> pref
        }
    }

    /**
     * ElevenLabs can infer language when this is null. For System default and
     * explicit choices we still send a concrete language code for predictable
     * testing across both engines.
     */
    fun elevenLabsLanguageCode(ctx: Context): String? {
        val pref = rawLanguage(ctx)
        return when (pref) {
            LANG_DETECT -> null
            LANG_SYSTEM -> systemLanguage()
            else -> pref
        }
    }

    private fun systemLanguage(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    private fun detectLanguage(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val spanishMarkers = listOf(
            "á", "é", "í", "ó", "ú", "ñ", "¿", "¡",
            " de ", " que ", " para ", " por ", " con ", " una ", " los ", " las ",
            " el ", " la ", " en ", " es ", " estoy ", " gracias ", " mañana ",
            " mensaje ", " llamada "
        )
        val englishMarkers = listOf(
            " the ", " and ", " for ", " with ", " you ", " your ", " are ",
            " thanks ", " tomorrow ", " message ", " call ", " meeting "
        )

        val spanishScore = spanishMarkers.count { lower.contains(it) }
        val englishScore = englishMarkers.count { lower.contains(it) }
        return when {
            spanishScore > englishScore -> "es"
            englishScore > spanishScore -> "en"
            else -> null
        }
    }

    /**
     * Per-app opt-out: default ON — all apps announce unless the user explicitly
     * disables them in the "Choose apps" screen. The service's IGNORED_PACKAGES
     * set and the ongoing/foreground-service flag checks handle obvious noise.
     */
    fun isAppEnabled(ctx: Context, packageName: String): Boolean =
        prefs(ctx).getBoolean(KEY_APP_PREFIX + packageName, true)

    /**
     * Returns true if the in-ear gate (only_in_ear) is satisfied — either the
     * pref is off, or the BLE manager confirms at least one bud is in ear.
     * If the pref is on but no BLE info is available (AirPodsService not
     * running, or no recent broadcast), returns false (conservative skip).
     */
    fun quietHoursEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_QUIET_ENABLED, false)

    fun quietMode(ctx: Context): String =
        prefs(ctx).getString(KEY_QUIET_MODE, QUIET_MODE_MANUAL) ?: QUIET_MODE_MANUAL

    /** Default quiet window: 22:00 → 07:00. */
    fun quietStart(ctx: Context): Int = prefs(ctx).getInt(KEY_QUIET_START, 22 * 60)
    fun quietEnd(ctx: Context): Int = prefs(ctx).getInt(KEY_QUIET_END, 7 * 60)

    fun setQuietStart(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_QUIET_START, minutes).apply()
    }
    fun setQuietEnd(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_QUIET_END, minutes).apply()
    }

    /**
     * Returns true if announcements may proceed (NOT inside quiet hours).
     * - "manual" mode: time-of-day window (may wrap past midnight).
     * - "system" mode: follows Android's DND (interruption filter).
     */
    fun passesQuietHoursGate(ctx: Context): Boolean {
        if (!quietHoursEnabled(ctx)) return true
        return when (quietMode(ctx)) {
            QUIET_MODE_SYSTEM -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                val filter = nm.currentInterruptionFilter
                // INTERRUPTION_FILTER_ALL = 1 (DND off). Anything else means
                // some level of DND is active → suppress announcements.
                // INTERRUPTION_FILTER_UNKNOWN = 0 (no notification access);
                // treat as "no info, don't suppress".
                filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
                    filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            else -> {
                val now = java.util.Calendar.getInstance()
                val nowMins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    now.get(java.util.Calendar.MINUTE)
                val start = quietStart(ctx)
                val end = quietEnd(ctx)
                if (start == end) return true
                val inWindow = if (start < end) {
                    nowMins in start until end
                } else {
                    nowMins >= start || nowMins < end
                }
                !inWindow
            }
        }
    }

    fun passesInEarGate(ctx: Context): Boolean {
        if (!onlyInEar(ctx)) return true
        val service = ServiceManager.getService() ?: run {
            Log.d(TAG, "In-ear status unavailable; deferring to audio route guard")
            return true
        }
        val status = try {
            service.bleManager.getMostRecentStatus()
        } catch (e: UninitializedPropertyAccessException) {
            null
        } ?: run {
            Log.d(TAG, "No recent in-ear status; deferring to audio route guard")
            return true
        }
        return status.isLeftInEar == true || status.isRightInEar == true
    }

    fun setAppEnabled(ctx: Context, packageName: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_APP_PREFIX + packageName, enabled).apply()
    }

    fun ttsEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_TTS_ENGINE, TTS_ENGINE_SYSTEM) ?: TTS_ENGINE_SYSTEM

    fun elevenLabsApiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_ELEVENLABS_API_KEY, "") ?: ""

    fun elevenLabsVoiceId(ctx: Context): String =
        prefs(ctx).getString(KEY_ELEVENLABS_VOICE_ID, ElevenLabsEngine.DEFAULT_VOICE_ID)
            ?: ElevenLabsEngine.DEFAULT_VOICE_ID

    /**
     * Localised low-battery alert. component is "left", "right", "case".
     */
    fun batteryAlert(language: String, component: String, level: Int): String {
        val lang = language.lowercase()
        return when (lang) {
            "es" -> when (component) {
                "left" -> "Batería del AirPod izquierdo al $level por ciento"
                "right" -> "Batería del AirPod derecho al $level por ciento"
                "case" -> "Batería del estuche de los AirPods al $level por ciento"
                else -> "Batería al $level por ciento"
            }
            "fr" -> when (component) {
                "left" -> "Batterie de l'AirPod gauche à $level pour cent"
                "right" -> "Batterie de l'AirPod droit à $level pour cent"
                "case" -> "Batterie du boîtier des AirPods à $level pour cent"
                else -> "Batterie à $level pour cent"
            }
            "de" -> when (component) {
                "left" -> "Batterie des linken AirPods bei $level Prozent"
                "right" -> "Batterie des rechten AirPods bei $level Prozent"
                "case" -> "Batterie des AirPods Ladecase bei $level Prozent"
                else -> "Akku bei $level Prozent"
            }
            "it" -> when (component) {
                "left" -> "Batteria dell'AirPod sinistro al $level percento"
                "right" -> "Batteria dell'AirPod destro al $level percento"
                "case" -> "Batteria della custodia degli AirPods al $level percento"
                else -> "Batteria al $level percento"
            }
            "pt" -> when (component) {
                "left" -> "Bateria do AirPod esquerdo a $level por cento"
                "right" -> "Bateria do AirPod direito a $level por cento"
                "case" -> "Bateria do estojo dos AirPods a $level por cento"
                else -> "Bateria a $level por cento"
            }
            else -> when (component) {
                "left" -> "Left AirPod battery is $level percent"
                "right" -> "Right AirPod battery is $level percent"
                "case" -> "AirPods case battery is $level percent"
                else -> "Battery at $level percent"
            }
        }
    }

    /**
     * Localised call announcement strings. Returns Pair(withName, withoutNumber)
     * formatted with optional name placeholder.
     */
    fun callAnnouncement(language: String, name: String?): String {
        val lang = language.lowercase()
        val (withName, generic) = when (lang) {
            "es" -> "Llamada entrante de %s" to "Llamada entrante"
            "en" -> "Incoming call from %s" to "Incoming call"
            "fr" -> "Appel entrant de %s" to "Appel entrant"
            "de" -> "Anruf von %s" to "Eingehender Anruf"
            "it" -> "Chiamata in arrivo da %s" to "Chiamata in arrivo"
            "pt" -> "Chamada de %s" to "Chamada recebida"
            else -> "Incoming call from %s" to "Incoming call"
        }
        return if (name.isNullOrBlank()) generic else withName.format(name)
    }
}
