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

package me.kavishdevar.librepods.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import me.kavishdevar.librepods.utils.AnnouncementPrefs
import me.kavishdevar.librepods.utils.ElevenLabsEngine
import me.kavishdevar.librepods.utils.TtsEngine

/**
 * Reads incoming notifications aloud through the AirPods (or whichever audio
 * sink Android is currently routing to).
 *
 * Phase A: minimal — speak everything, no filtering. This is the validation
 * pass; per-app filtering and quiet hours come in Phase C.
 *
 * Requires Notification access (Settings → Apps → Special access → Notification
 * access). The same grant flow used for TeamsNotifListener applies — the user
 * must enable LibrePods in that list.
 */
class NotificationAnnouncementService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifAnnounceSvc"

        // Skip our own foreground service notification, MIUI/OneUI system
        // notifications, and ongoing/group-summary entries that aren't really
        // user-visible events.
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "me.kavishdevar.librepods",
            "me.kavishdevar.librepods.announce",
        )

        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            // Each NotificationListenerService in the manifest gets its own entry in
            // Android's Notification Access settings (distinguished by android:label).
            // We need THIS specific service enabled; TeamsNotifListener being on
            // doesn't bind us. Trim() each entry — Settings.Secure value can have
            // trailing newlines.
            val cn = "${context.packageName}/${NotificationAnnouncementService::class.java.name}"
            return flat.split(":").any { it.trim() == cn }
        }

        fun openAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        migrateLegacyPrefs()
    }

    /**
     * Remove the old combined skip-during-call-media preference so the new
     * separate defaults (skipDuringCall=true, skipDuringMedia=false) take effect
     * for users upgrading from the previous build.
     */
    private fun migrateLegacyPrefs() {
        val prefs = AnnouncementPrefs.prefs(applicationContext)
        if (prefs.contains("announce_skip_during_call_media")) {
            prefs.edit().remove("announce_skip_during_call_media").apply()
            Log.d(TAG, "Migrated: removed legacy combined skip pref")
        }
        // If the user never explicitly changed skipDuringMedia, reset it to the
        // new default (false). We detect "never changed" by checking if the old
        // combined key was present — which we just removed above; if it was, the
        // split keys may have been written as `true` by the old build.
        // Simply clear the split key so the new default (false) applies cleanly.
        if (!prefs.contains("announce_skip_during_call_media")) {
            // Key was just removed → this is an upgrading user. Reset the media key.
            prefs.edit().remove(AnnouncementPrefs.KEY_SKIP_DURING_MEDIA).apply()
            Log.d(TAG, "Migrated: reset skipDuringMedia to new default (false)")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldAnnounce(sbn)) return
        val text = buildAnnouncement(sbn) ?: return
        Log.d(TAG, "Announcing from ${sbn.packageName}: \"$text\"")
        announceText(text)
    }

    private fun shouldAnnounce(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName in IGNORED_PACKAGES) return false
        val n = sbn.notification ?: return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        if (!AnnouncementPrefs.isEnabled(applicationContext)) return false
        // Per-app opt-out: default on. User can silence specific apps via "Choose apps".
        if (!AnnouncementPrefs.isAppEnabled(applicationContext, sbn.packageName)) return false

        if (AnnouncementPrefs.skipDuringCall(applicationContext) && isInActiveCall()) {
            Log.d(TAG, "Skip ${sbn.packageName} — call active")
            return false
        }
        if (AnnouncementPrefs.skipDuringMedia(applicationContext) && isMediaPlaying()) {
            Log.d(TAG, "Skip ${sbn.packageName} — media playing")
            return false
        }
        if (!AnnouncementPrefs.passesInEarGate(applicationContext)) {
            Log.d(TAG, "Skip ${sbn.packageName} — in-ear gate not satisfied")
            return false
        }
        if (!AnnouncementPrefs.passesQuietHoursGate(applicationContext)) {
            Log.d(TAG, "Skip ${sbn.packageName} — quiet hours active")
            return false
        }
        return true
    }

    private fun isInActiveCall(): Boolean {
        val am = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
        ) return true
        val telephony = applicationContext.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        if (telephony.callState != TelephonyManager.CALL_STATE_IDLE) return true
        return false
    }

    /**
     * Returns true only when a real user media session (Spotify, YouTube, etc.)
     * is actively playing. Deliberately does NOT use AudioManager.isMusicActive
     * because TTS itself outputs to the music stream, which would cause every
     * announcement to block the next one in a circular loop.
     *
     * We pass our own ComponentName since we extend NotificationListenerService,
     * which grants the required MEDIA_CONTENT_CONTROL privilege for this call.
     */
    private fun isMediaPlaying(): Boolean {
        return try {
            val msm = applicationContext
                .getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false
            val cn = ComponentName(applicationContext, NotificationAnnouncementService::class.java)
            val sessions = msm.getActiveSessions(cn)
            sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        } catch (e: Exception) {
            Log.w(TAG, "isMediaPlaying check failed: ${e.message}")
            false  // conservative: don't block if we can't tell
        }
    }

    private fun buildAnnouncement(sbn: StatusBarNotification): String? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return null

        val appLabel = appLabelFor(sbn.packageName)
        val mode = AnnouncementPrefs.contentMode(applicationContext)
        val body = when (mode) {
            AnnouncementPrefs.CONTENT_TITLE_ONLY ->
                if (title.isNotEmpty()) title else text
            else -> when {
                title.isNotEmpty() && text.isNotEmpty() -> "$title. $text"
                title.isNotEmpty() -> title
                else -> text
            }
        }
        return "$appLabel: $body"
    }

    /**
     * Route to ElevenLabs or system TTS based on user preference.
     * ElevenLabs failures fall back to system TTS automatically.
     */
    private fun announceText(text: String) {
        val ctx = applicationContext
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
                        Log.w(TAG, "ElevenLabs failed ($reason), falling back to system TTS")
                        TtsEngine.speak(ctx, text, languageForSystemTts)
                    }
                )
                return
            }
            Log.w(TAG, "ElevenLabs selected but no API key — using system TTS")
        }
        TtsEngine.speak(ctx, text, languageForSystemTts)
    }

    private fun appLabelFor(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
