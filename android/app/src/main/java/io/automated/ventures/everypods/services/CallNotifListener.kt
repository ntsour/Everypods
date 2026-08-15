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

package io.automated.ventures.everypods.services

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

/**
 * Watches ongoing-call notifications from VoIP apps (Teams, Viber, WhatsApp, ...) and
 * caches their action PendingIntents. AirPodsService can then call [setMuted] /
 * [hangUp] / [answerCall] / [declineCall] to fire the right one — the app
 * reacts as if the user tapped the button in the notification, keeping its
 * in-app UI in sync.
 *
 * Action resolution uses a three-tier pipeline:
 *
 *   Tier 1 — Notification.CallStyle extras (API 31+, locale-independent, structural).
 *            Reads EXTRA_ANSWER_INTENT, EXTRA_DECLINE_INTENT, EXTRA_HANG_UP_INTENT
 *            from notification.extras. These are stable keys regardless of locale,
 *            language, or notification button labels.
 *
 *   Tier 2 — Position convention. Falls back to actions[0]=decline, actions[1]=answer
 *            (matches Notification.CallStyle.forIncomingCall arg order). Used for
 *            apps that don't set CallStyle extras (rare on modern Android).
 *
 *   Tier 3 — Keyword match (multi-locale title substring). Last-resort safety net
 *            for legacy apps and for mute/unmute (CallStyle has no mute extra).
 *
 * Requires the user to grant Notification access (Settings → Apps → Special
 * access → Notification access). Use [isAccessGranted] / [openAccessSettings]
 * from UI to drive the grant flow.
 */
class CallNotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "CallNotifListener"

        private val WATCHED_PACKAGES = setOf(
            "com.microsoft.teams",
            "com.microsoft.teams.ipphone",
            "com.microsoft.teams2",
            "com.viber.voip",
            "com.whatsapp",
        )

        // ─── Keyword lists (tier 3) ───────────────────────────────────────────
        // Lower-case substrings checked against action title. ASCII-folded and
        // diacritic variants included where common. Order matters when checking
        // (unmute must be tried before mute — substring trap).
        // Coverage: en, es, fr, de, it, pt, nl, pl, tr, ru, el, zh-CN, zh-TW, ja, ko, ar, he.

        private val ANSWER_KEYWORDS = listOf(
            // English
            "answer", "accept", "take call", "pick up",
            // Spanish
            "contestar", "atender", "aceptar", "responder",
            // French
            "répondre", "repondre", "accepter", "prendre l'appel",
            // German
            "annehmen", "abnehmen",
            // Italian
            "rispondi", "accetta",
            // Portuguese
            "atender", "aceitar",
            // Dutch
            "beantwoorden", "opnemen", "accepteren",
            // Polish
            "odbierz", "akceptuj", "przyjmij",
            // Turkish
            "cevapla", "kabul et",
            // Russian
            "ответить", "принять",
            // Greek
            "απάντηση", "αποδοχή",
            // Chinese (simplified / traditional)
            "接听", "接聽", "接受",
            // Japanese
            "応答", "出る", "受ける",
            // Korean
            "받기", "응답", "수락",
            // Arabic
            "الرد", "قبول",
            // Hebrew
            "ענה", "קבל",
        )

        private val DECLINE_KEYWORDS = listOf(
            // English
            "decline", "reject", "dismiss", "ignore",
            // Spanish
            "rechazar", "declinar", "ignorar",
            // French
            "refuser", "rejeter", "décliner", "decliner",
            // German
            "ablehnen", "abweisen",
            // Italian
            "rifiuta", "declina",
            // Portuguese
            "rejeitar", "recusar",
            // Dutch
            "weigeren", "afwijzen",
            // Polish
            "odrzuć", "odrzuc",
            // Turkish
            "reddet", "yoksay",
            // Russian
            "отклонить", "отказать",
            // Greek
            "απόρριψη", "άρνηση",
            // Chinese
            "拒绝", "拒絕",
            // Japanese
            "拒否", "拒絶",
            // Korean
            "거절", "거부",
            // Arabic
            "رفض",
            // Hebrew
            "דחה",
        )

        private val HANG_UP_KEYWORDS = listOf(
            // English
            "hang up", "hangup", "end call", "end",
            // Spanish
            "colgar", "finalizar", "terminar",
            // French
            "raccrocher", "terminer",
            // German
            "auflegen", "beenden",
            // Italian
            "riaggancia", "termina", "chiudi",
            // Portuguese
            "desligar", "terminar", "encerrar",
            // Dutch
            "ophangen", "beëindigen", "beeindigen",
            // Polish
            "zakończ", "zakoncz", "rozłącz", "rozlacz",
            // Turkish
            "kapat", "sonlandır", "sonlandir",
            // Russian
            "завершить", "положить трубку",
            // Greek
            "τερματισμός", "τερματισμος", "κλείσιμο", "κλεισιμο",
            // Chinese
            "挂断", "掛斷", "结束", "結束",
            // Japanese
            "切断", "終了",
            // Korean
            "종료", "끊기",
            // Arabic
            "إنهاء",
            // Hebrew
            "סיים שיחה",
        )

        // Mute / Unmute keywords — note "unmute" must be checked before "mute"
        // since lowercase "unmute".contains("mute") is true.
        private val UNMUTE_KEYWORDS = listOf(
            // English
            "unmute",
            // Spanish
            "activar micrófono", "activar microfono", "dejar de silenciar",
            // French
            "réactiver", "reactiver", "activer le micro",
            // German
            "stummschaltung aufheben", "laut schalten",
            // Italian
            "attiva microfono", "riattiva",
            // Portuguese
            "ativar", "ativar microfone",
            // Dutch
            "microfoon aan", "dempen opheffen",
            // Polish
            "wyłącz wyciszenie", "wylacz wyciszenie",
            // Turkish
            "sesi aç", "sesi ac",
            // Russian
            "включить микрофон",
            // Greek
            "άρση σίγασης", "αρση σιγασης",
            // Chinese
            "取消静音", "取消靜音",
            // Japanese
            "ミュート解除", "消音解除",
            // Korean
            "음소거 해제",
            // Arabic
            "إلغاء الكتم",
            // Hebrew
            "בטל השתקה",
        )

        private val MUTE_KEYWORDS = listOf(
            // English
            "mute", "silence",
            // Spanish
            "silenciar", "silenciar micrófono", "silenciar microfono",
            // French
            "muet", "couper le micro",
            // German
            "stumm", "stummschalten",
            // Italian
            "disattiva microfono", "muto",
            // Portuguese
            "silenciar", "mudo",
            // Dutch
            "dempen", "microfoon uit",
            // Polish
            "wycisz",
            // Turkish
            "sesi kapat", "sustur",
            // Russian
            "отключить микрофон", "заглушить",
            // Greek
            "σίγαση", "σιγαση",
            // Chinese
            "静音", "靜音",
            // Japanese
            "ミュート", "消音",
            // Korean
            "음소거",
            // Arabic
            "كتم",
            // Hebrew
            "השתק",
        )

        // ─── Action cache ─────────────────────────────────────────────────────
        @Volatile private var muteAction: Notification.Action? = null
        @Volatile private var unmuteAction: Notification.Action? = null
        @Volatile private var hangUpAction: Notification.Action? = null
        @Volatile private var answerAction: Notification.Action? = null
        @Volatile private var declineAction: Notification.Action? = null
        @Volatile private var lastSeenKey: String? = null

        /** Canonical mute state derived from the watched VoIP app's notification buttons.
         *  true  = notification currently shows "Unmute" (i.e. mic is muted in the app)
         *  false = notification currently shows "Mute"   (i.e. mic is unmuted in the app)
         *  null  = no active call notification seen yet, or both/neither button visible
         *
         *  Applies to any watched package (Teams, Viber, WhatsApp, …) — the name is
         *  intentionally generic, no longer Teams-specific. */
        @Volatile var muteShownByAppNotif: Boolean? = null
            private set

        /** True while a ringing VoIP call notification (answer + decline cached) is
         *  posted by any watched package. Set by [handle], cleared by
         *  [onNotificationRemoved] or when the notification transitions to active
         *  (answer/decline replaced by mute/hangUp). Used by [AirPodsService.isInAnyCall]
         *  to detect VoIP rings that don't trigger TelephonyManager / Telecom
         *  (Viber, some Teams configs). */
        @Volatile var isVoipNotifRinging: Boolean = false
            private set

        /** True while an active VoIP call notification (mute/hangUp cached) is
         *  posted by any watched package. Same purpose as [isVoipNotifRinging]
         *  but for the post-answer state. */
        @Volatile var isVoipNotifActive: Boolean = false
            private set

        /** Called on the main thread whenever the app's in-notification mute state
         *  flips (not on first detection — only on subsequent changes). */
        @Volatile var onMuteStateChanged: ((muted: Boolean) -> Unit)? = null

        /** Returns the canonical mute state derived from the active VoIP app's
         *  notification button label, or null if unknown / no active call. */
        fun isMuteShownByAppNotif(): Boolean? = muteShownByAppNotif

        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = "${context.packageName}/${CallNotifListener::class.java.name}"
            return flat.split(":").any { it.trim() == cn }
        }

        fun openAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        // ─── Dispatch helpers ─────────────────────────────────────────────────
        // All four fire the cached PendingIntent via PendingIntent.send() with
        // ActivityOptions.setPendingIntentBackgroundActivityStartMode(ALLOWED)
        // on API 34+. This lifts the Background Activity Launch (BAL)
        // restriction that Android 14+ otherwise applies to Activity
        // PendingIntents fired from a foreground service — required for apps
        // like Viber whose Answer/Hang up PIs target a fullscreen call Activity.
        // The ActivityOptions bundle is harmless for Broadcast / Service PIs.

        fun setMuted(muted: Boolean): Boolean {
            val action = if (muted) muteAction else unmuteAction
            if (action == null) {
                Log.d(TAG, "setMuted($muted): no cached action")
                return false
            }
            return sendActionPendingIntent(action, "setMuted($muted)")
        }

        fun hangUp(): Boolean {
            val action = hangUpAction ?: run {
                Log.d(TAG, "hangUp(): no cached action")
                return false
            }
            return sendActionPendingIntent(action, "hangUp")
        }

        fun answerCall(): Boolean {
            val action = answerAction ?: run {
                Log.d(TAG, "answerCall(): no cached action")
                return false
            }
            return sendActionPendingIntent(action, "answerCall")
        }

        fun declineCall(): Boolean {
            val action = declineAction ?: run {
                Log.d(TAG, "declineCall(): no cached action")
                return false
            }
            return sendActionPendingIntent(action, "declineCall")
        }

        private fun sendActionPendingIntent(
            action: Notification.Action,
            label: String,
        ): Boolean = try {
            val ctx = ServiceManager.getService()?.applicationContext
            val fillIn = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val optsBundle = if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
            } else null
            action.actionIntent.send(ctx, 0, fillIn, null, null, null, optsBundle)
            Log.d(TAG, "$label: fired ${action.title}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$label failed: ${t.message}")
            false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
        // Re-scan currently posted notifications so we pick up an in-progress call.
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "scan active notifications failed: ${t.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        if (sbn.key == lastSeenKey) {
            Log.d(TAG, "Call notification removed; clearing cached actions")
            muteAction = null
            unmuteAction = null
            hangUpAction = null
            answerAction = null
            declineAction = null
            lastSeenKey = null
            muteShownByAppNotif = null
            isVoipNotifRinging = false
            isVoipNotifActive = false
        }
    }

    /**
     * Resolve a [PendingIntent] from [Notification.CallStyle] extras into a
     * [Notification.Action] suitable for caching. Prefers an existing Action
     * in [actions] whose PI matches; otherwise synthesises an anonymous wrapper
     * Action (we never display the title — it's used only for logs).
     */
    private fun resolveOrSynthesizeAction(
        actions: Array<Notification.Action>?,
        pi: PendingIntent?,
        synthLabel: String,
    ): Notification.Action? {
        if (pi == null) return null
        // Tier 1a — match the PI to an existing Action (preserves any extras
        // or RemoteInputs the app attached to that button).
        actions?.firstOrNull { it.actionIntent == pi }?.let { return it }
        // Tier 1b — synthesise. Title is for log readability only.
        return Notification.Action.Builder(/* icon = */ 0, synthLabel, pi).build()
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        val n = sbn.notification ?: return
        val actions: Array<Notification.Action> = n.actions ?: emptyArray()

        // Verbose log for debugging.
        val titles = actions.map { it.title?.toString().orEmpty() }
        Log.d(TAG, "Scanning ${sbn.packageName} notification (key=${sbn.key}, actions=${titles})")

        // ─── Tier 1 — CallStyle extras (locale-independent, structural) ──────
        val extras = n.extras
        val callTypeRaw = try { extras.getInt(Notification.EXTRA_CALL_TYPE, 0) } catch (_: Throwable) { 0 }
        val answerPi  = getPendingIntentExtra(extras, Notification.EXTRA_ANSWER_INTENT)
        val declinePi = getPendingIntentExtra(extras, Notification.EXTRA_DECLINE_INTENT)
        val hangUpPi  = getPendingIntentExtra(extras, Notification.EXTRA_HANG_UP_INTENT)

        var foundAnswer  = resolveOrSynthesizeAction(actions, answerPi,  "<answer>")
        var foundDecline = resolveOrSynthesizeAction(actions, declinePi, "<decline>")
        var foundHangUp  = resolveOrSynthesizeAction(actions, hangUpPi,  "<hangup>")
        var foundMute: Notification.Action? = null
        var foundUnmute: Notification.Action? = null

        val tier1Hit = (foundAnswer != null) || (foundDecline != null) || (foundHangUp != null)

        // ─── Tier 3 — Keyword match (multi-locale title substring) ───────────
        // Runs BEFORE tier 2 so that title-matchable actions are correctly
        // classified before position guessing kicks in. Without this, an active
        // call notification whose [Unmute, Hang up] actions get walked by
        // position-based tier 2 would mis-cache Unmute as "decline" and Hang up
        // as "answer".
        for (a in actions) {
            val title = a.title?.toString().orEmpty().lowercase(Locale.ROOT)
            if (title.isEmpty()) continue
            // Order matters: unmute before mute (substring trap).
            when {
                foundUnmute == null && UNMUTE_KEYWORDS.any { title.contains(it) }  -> foundUnmute  = a
                foundMute   == null && MUTE_KEYWORDS.any   { title.contains(it) }  -> foundMute    = a
                foundHangUp == null && HANG_UP_KEYWORDS.any{ title.contains(it) }  -> foundHangUp  = a
                foundAnswer == null && ANSWER_KEYWORDS.any { title.contains(it) }  -> foundAnswer  = a
                foundDecline== null && DECLINE_KEYWORDS.any{ title.contains(it) }  -> foundDecline = a
            }
        }

        // ─── Tier 2 — Position convention (last-resort only) ─────────────────
        // Strictly requires explicit CALL_TYPE_INCOMING and that tier 1 + tier 3
        // didn't resolve at least one of answer/decline. Without explicit
        // CALL_TYPE_INCOMING we cannot distinguish ringing from active by shape,
        // and walking actions by position would mis-cache active-call buttons
        // (Teams active notif = [Unmute, Hang up]: position-based ringing
        // mapping would put Unmute under "decline" and Hang up under "answer").
        if (n.category == Notification.CATEGORY_CALL &&
            callTypeRaw == Notification.CallStyle.CALL_TYPE_INCOMING &&
            foundAnswer == null && foundDecline == null &&
            actions.size >= 2
        ) {
            Log.d(TAG, "tier2: applying position convention (incoming, no tier1/3 hit, ${actions.size} actions)")
            foundDecline = actions[0]
            foundAnswer  = actions[1]
        }

        // ─── Cache update ─────────────────────────────────────────────────────
        // Guard against false positives from non-call notifications that
        // coincidentally have similarly-named buttons (e.g. WhatsApp message
        // notifications also have a "Mute" button for conversation muting).
        // Require at least 2 call-related actions OR a tier-1 CallStyle hit
        // (which is structural and trustworthy on its own) before caching.
        val callActionCount = listOf(
            foundMute, foundUnmute, foundHangUp, foundAnswer, foundDecline
        ).count { it != null }
        if (callActionCount == 0) return
        if (callActionCount < 2 && !tier1Hit) {
            Log.d(TAG, "Ignoring ${sbn.packageName} notification with only $callActionCount call-related action(s) — likely not a call")
            return
        }

        muteAction = foundMute ?: muteAction
        unmuteAction = foundUnmute ?: unmuteAction
        hangUpAction = foundHangUp ?: hangUpAction
        answerAction = foundAnswer ?: answerAction
        declineAction = foundDecline ?: declineAction
        lastSeenKey = sbn.key
        Log.d(
            TAG,
            "Cached actions from ${sbn.packageName} (tier1Hit=$tier1Hit callType=$callTypeRaw): " +
                "mute=${foundMute?.title}, unmute=${foundUnmute?.title}, " +
                "hangUp=${foundHangUp?.title}, answer=${foundAnswer?.title}, decline=${foundDecline?.title}"
        )

        // Maintain the ringing/active VoIP flags so AirPodsService.isInAnyCall()
        // can detect VoIP calls that bypass TelephonyManager / Telecom (Viber).
        // Ringing requires both answer + decline AND no active-call signals
        // (no hangUp/mute/unmute) — otherwise we're looking at an active call
        // whose actions happened to be mis-cached.
        val hasActiveSignals = foundHangUp != null || foundMute != null || foundUnmute != null
        val ringingNow = foundAnswer != null && foundDecline != null && !hasActiveSignals
        val activeNow = !ringingNow && hasActiveSignals
        if (ringingNow != isVoipNotifRinging) {
            Log.d(TAG, "isVoipNotifRinging: $isVoipNotifRinging → $ringingNow (${sbn.packageName})")
            isVoipNotifRinging = ringingNow
        }
        if (activeNow != isVoipNotifActive) {
            Log.d(TAG, "isVoipNotifActive: $isVoipNotifActive → $activeNow (${sbn.packageName})")
            isVoipNotifActive = activeNow
        }

        // Incoming ringing VoIP call detected. Self-managed VoIP apps
        // (WhatsApp, Viber) don't trigger TelephonyManager CALL_STATE_RINGING,
        // so the AirPods never enter HFP call state and setupStemActions()
        // is never called — meaning single press events aren't forwarded
        // to the app. Kick stem action setup here to re-apply the config.
        if (ringingNow) {
            Log.d(TAG, "Ringing VoIP call detected for ${sbn.packageName} — calling setupStemActions()")
            ServiceManager.getService()?.setupStemActions()
        }

        // Derive canonical mute state: "Unmute" button visible → currently muted,
        // "Mute" button visible → currently unmuted. Ambiguous if both or neither present.
        val newState: Boolean? = when {
            foundUnmute != null && foundMute == null -> true
            foundMute != null && foundUnmute == null -> false
            else -> null
        }
        if (newState != null && newState != muteShownByAppNotif) {
            val wasKnown = muteShownByAppNotif != null
            muteShownByAppNotif = newState
            if (wasKnown) {
                Log.d(TAG, "App-notif mute state changed → $newState (${sbn.packageName})")
                onMuteStateChanged?.invoke(newState)
            } else {
                Log.d(TAG, "App-notif initial mute state detected: $newState (${sbn.packageName})")
            }
        }
    }

    private fun getPendingIntentExtra(extras: android.os.Bundle?, key: String): PendingIntent? {
        if (extras == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(key, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(key) as? PendingIntent
            }
        } catch (_: Throwable) {
            null
        }
    }
}
