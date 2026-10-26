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
import android.content.res.Configuration
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale
import io.automated.ventures.everypods.R

/**
 * Watches ongoing-call notifications from VoIP apps (Teams, Viber, WhatsApp, ...) and
 * caches their action PendingIntents. AirPodsService can then call [setMuted] /
 * [hangUp] / [answerCall] / [declineCall] to fire the right one — the app
 * reacts as if the user tapped the button in the notification, keeping its
 * in-app UI in sync.
 *
 * Action resolution uses a structural-first pipeline:
 *
 *   Tier 1 — Notification.CallStyle extras (API 31+, locale-independent, structural).
 *            Reads EXTRA_ANSWER_INTENT, EXTRA_DECLINE_INTENT, EXTRA_HANG_UP_INTENT
 *            from notification.extras. These are stable keys regardless of locale,
 *            language, or notification button labels.
 *
 *   Fallback — action metadata, verified app resources/icons and whole-label
 *              keyword matching. Never infer actions from array position.
 *
 * Requires the user to grant Notification access (Settings → Apps → Special
 * access → Notification access). Use [isAccessGranted] / [openAccessSettings]
 * from UI to drive the grant flow.
 */
class CallNotifListener : NotificationListenerService() {

    companion object {
        private const val TAG = "CallNotifListener"
        private val ACTION_WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        private val WATCHED_PACKAGES = setOf(
            "com.microsoft.teams",
            "com.microsoft.teams.ipphone",
            "com.microsoft.teams2",
            "com.viber.voip",
            "com.whatsapp",
        )

        // ─── Keyword lists (tier 3) ───────────────────────────────────────────
        // Lower-case labels checked against action title. ASCII-folded and
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
            // Ukrainian / Vietnamese: installed Viber CallStyle resource values.
            "відповісти", "trả lời",
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
            // Ukrainian / Vietnamese: installed Viber CallStyle resource values.
            "відхилити", "từ chối",
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
            "reactivar audio", "reactivar el audio",
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

        // Semantic metadata and Teams resource entry names are independent of UI locale.
        internal fun muteStateForAction(semantic: Int, teamsIconName: String?): Boolean? = when {
            semantic == Notification.Action.SEMANTIC_ACTION_UNMUTE -> true
            semantic == Notification.Action.SEMANTIC_ACTION_MUTE -> false
            teamsIconName == "icn_callaction_audio_off" -> true
            teamsIconName == "icn_callaction_audio" -> false
            else -> null
        }

        internal fun readTeamsMuteLabels(
            teams: Context, packageName: String, tags: Array<String>,
        ): Map<String, Boolean> {
            val labels = mutableMapOf<String, Boolean>()
            for (tag in tags) {
                val config = Configuration(teams.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val localized = teams.createConfigurationContext(config).resources
                for ((name, muted) in listOf(
                    "notifications_call_mute_title" to false,
                    "notifications_call_unmute_title" to true,
                )) {
                    val id = localized.getIdentifier(name, "string", packageName)
                    if (id != 0) {
                        val label = normalizeActionLabel(localized.getString(id))
                        labels[label] = muted
                    }
                }
            }
            return labels
        }

        private fun normalizeActionLabel(label: String): String =
            label.lowercase(Locale.ROOT).replace(ACTION_WORD_SEPARATORS, " ").trim()

        internal fun readTeamsCallLabels(
            teams: Context, packageName: String, tags: Array<String>,
        ): Map<String, String> {
            val labels = mutableMapOf<String, String>()
            for (tag in tags) {
                val config = Configuration(teams.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val localized = teams.createConfigurationContext(config).resources
                for ((name, role) in listOf(
                    "notifications_pre_call_answer_audio" to "answer",
                    "notifications_pre_call_decline" to "decline",
                )) {
                    val id = localized.getIdentifier(name, "string", packageName)
                    if (id != 0) labels[normalizeActionLabel(localized.getString(id))] = role
                }
            }
            return labels
        }

        private enum class CallStage { INCOMING, ONGOING, SCREENING, UNKNOWN }

        /** All actions and state belong to one exact notification owner. */
        private data class CallSnapshot(
            val key: String,
            val packageName: String,
            val postTime: Long,
            val stage: CallStage,
            val mute: Notification.Action?,
            val unmute: Notification.Action?,
            val hangUp: Notification.Action?,
            val answer: Notification.Action?,
            val decline: Notification.Action?,
        )

        private enum class CallActionKind { ANSWER, TELECOM_ANSWER, DECLINE, HANG_UP }
        private data class PendingCallAction(
            val key: String,
            val stage: CallStage,
            val kind: CallActionKind,
            val actionIntent: PendingIntent?,
            val startedAtElapsedMs: Long,
        )
        private const val CALL_ACTION_PENDING_WINDOW_MS = 5_000L
        internal const val ANSWER_TELECOM_FALLBACK_DELAY_MS = 800L

        private val snapshotLock = Any()
        private val callSnapshots = linkedMapOf<String, CallSnapshot>()
        private var pendingCallAction: PendingCallAction? = null
        @Volatile private var notificationListenerContext: Context? = null

        private fun CallSnapshot.intentFor(kind: CallActionKind): PendingIntent? = when (kind) {
            CallActionKind.ANSWER, CallActionKind.TELECOM_ANSWER -> answer?.actionIntent
            CallActionKind.DECLINE -> decline?.actionIntent
            CallActionKind.HANG_UP -> hangUp?.actionIntent
        }

        private fun currentCallSnapshot(): CallSnapshot? = synchronized(snapshotLock) {
            val incoming = callSnapshots.values.filter { it.stage == CallStage.INCOMING }
            val candidates = if (incoming.isNotEmpty()) incoming else
                callSnapshots.values.filter { it.stage == CallStage.ONGOING }
            candidates.maxWithOrNull(compareBy<CallSnapshot> { it.postTime }.thenBy { it.key })
        }

        /** Whether Telecom fallback can safely target the only observed call. */
        fun hasUnambiguousVoipCallTarget(): Boolean = synchronized(snapshotLock) {
            callSnapshots.values.count {
                it.stage == CallStage.INCOMING || it.stage == CallStage.ONGOING
            } == 1
        }

        fun hasPendingCallAction(): Boolean = synchronized(snapshotLock) {
            pendingCallAction?.let {
                SystemClock.elapsedRealtime() - it.startedAtElapsedMs < CALL_ACTION_PENDING_WINDOW_MS
            } == true
        }

        /** Claim the delayed Telecom answer route only if the notification Answer
         *  action did not move this same, sole call out of its incoming stage. */
        internal fun claimTelecomAnswerFallback(
            activeCallObserved: Boolean,
            nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        ): Boolean = synchronized(snapshotLock) {
            // Telecom/audio can report connection before the app updates its
            // incoming notification. A headset toggle at that point can hang up.
            if (activeCallObserved) return@synchronized false
            val pending = pendingCallAction ?: return@synchronized false
            val current = currentCallSnapshot()
            val eligible = pending.kind == CallActionKind.ANSWER &&
                nowElapsedMs - pending.startedAtElapsedMs >= ANSWER_TELECOM_FALLBACK_DELAY_MS &&
                nowElapsedMs - pending.startedAtElapsedMs < CALL_ACTION_PENDING_WINDOW_MS &&
                current?.key == pending.key && current.stage == CallStage.INCOMING &&
                callSnapshots.values.count {
                    it.stage == CallStage.INCOMING || it.stage == CallStage.ONGOING
                } == 1
            if (!eligible) return@synchronized false

            pendingCallAction = pending.copy(kind = CallActionKind.TELECOM_ANSWER)
            Log.d(TAG, "Notification Answer did not transition; claimed Telecom fallback for ${pending.key}")
            true
        }

        internal fun cancelPendingTelecomAnswerFallback() {
            synchronized(snapshotLock) {
                if (pendingCallAction?.kind == CallActionKind.TELECOM_ANSWER) pendingCallAction = null
            }
        }

        fun beginCurrentHangUpFallback(): Boolean {
            val snapshot = currentCallSnapshot()
            if (snapshot?.stage != CallStage.ONGOING) return false
            return claimCallAction(snapshot, CallActionKind.HANG_UP, "Telecom hang-up")
        }

        fun cancelPendingHangUpFallback() {
            synchronized(snapshotLock) {
                if (pendingCallAction?.kind == CallActionKind.HANG_UP) pendingCallAction = null
            }
        }

        private fun publishSnapshot(key: String, snapshot: CallSnapshot?) {
            var changedMuteState: Boolean? = null
            var muteStateChanged = false
            var callStateChanged = false
            synchronized(snapshotLock) {
                val previous = callSnapshots[key]
                if (snapshot == null) callSnapshots.remove(key)
                else callSnapshots[key] = snapshot

                val pending = pendingCallAction
                if (pending != null && (
                        pending.key == key && (snapshot == null || previous?.stage != snapshot.stage ||
                            snapshot.intentFor(pending.kind) != pending.actionIntent)
                    )) {
                    Log.d(TAG, "Clearing pending ${pending.kind} for ${pending.key}; call action changed or notification was removed")
                    pendingCallAction = null
                }

                val ringing = callSnapshots.values.any { it.stage == CallStage.INCOMING }
                val active = callSnapshots.values.any { it.stage == CallStage.ONGOING }
                callStateChanged = ringing != isVoipNotifRinging || active != isVoipNotifActive
                isVoipNotifRinging = ringing
                isVoipNotifActive = active

                val selected = currentCallSnapshot()
                val newMuteState = if (selected?.stage == CallStage.ONGOING) when {
                    selected.unmute != null && selected.mute == null -> true
                    selected.mute != null && selected.unmute == null -> false
                    else -> null
                } else null
                if (newMuteState != muteShownByAppNotif) {
                    muteShownByAppNotif = newMuteState
                    changedMuteState = newMuteState
                    muteStateChanged = true
                }
            }

            if (callStateChanged) onCallStateChanged?.invoke(isVoipNotifRinging, isVoipNotifActive)
            if (muteStateChanged) changedMuteState?.let { state -> onMuteStateChanged?.invoke(state) }
        }

        private fun clearSnapshots() {
            val hadCall: Boolean
            synchronized(snapshotLock) {
                hadCall = isVoipNotifRinging || isVoipNotifActive
                callSnapshots.clear()
                pendingCallAction = null
                isVoipNotifRinging = false
                isVoipNotifActive = false
                muteShownByAppNotif = null
            }
            if (hadCall) onCallStateChanged?.invoke(false, false)
        }

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
         *  is first detected or changes. */
        @Volatile var onMuteStateChanged: ((muted: Boolean) -> Unit)? = null

        /** Aggregate stage transitions, including calls with no Telecom callback. */
        @Volatile var onCallStateChanged: ((ringing: Boolean, active: Boolean) -> Unit)? = null

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
        // Dispatch only the resolved call's PendingIntent on behalf of a call
        // control gesture. API 36+ requires ALLOW_ALWAYS to use an existing
        // background-launch exemption (including SYSTEM_ALERT_WINDOW); legacy
        // ALLOWED no longer transfers that exemption. This opt-in does not grant
        // the exemption itself, and dispatch still does not confirm completion.

        fun setMuted(muted: Boolean): Boolean {
            val snapshot = currentCallSnapshot()
            if (snapshot?.stage != CallStage.ONGOING) {
                Log.d(TAG, "setMuted($muted): no current ongoing call")
                return false
            }
            val action = if (muted) snapshot.mute else snapshot.unmute
            if (action == null) {
                Log.d(TAG, "setMuted($muted): no cached action")
                return false
            }
            return sendActionPendingIntent(action, "setMuted($muted)")
        }

        fun hangUp(): Boolean {
            val snapshot = currentCallSnapshot()
            if (snapshot?.stage != CallStage.ONGOING) {
                Log.d(TAG, "hangUp(): no current ongoing call")
                return false
            }
            val action = snapshot.hangUp ?: run {
                Log.d(TAG, "hangUp(): no cached action")
                return false
            }
            return dispatchCallAction(snapshot, action, "hangUp", CallActionKind.HANG_UP)
        }

        fun answerCall(): Boolean {
            val snapshot = currentCallSnapshot()
            if (snapshot?.stage != CallStage.INCOMING) {
                Log.d(TAG, "answerCall(): no current incoming call")
                return false
            }
            val action = snapshot.answer ?: run {
                Log.d(TAG, "answerCall(): no cached action")
                return false
            }
            return dispatchCallAction(snapshot, action, "answerCall", CallActionKind.ANSWER)
        }

        fun declineCall(): Boolean {
            val snapshot = currentCallSnapshot()
            if (snapshot?.stage != CallStage.INCOMING) {
                Log.d(TAG, "declineCall(): no current incoming call")
                return false
            }
            val action = snapshot.decline ?: run {
                Log.d(TAG, "declineCall(): no cached action")
                return false
            }
            return dispatchCallAction(snapshot, action, "declineCall", CallActionKind.DECLINE)
        }

        private fun dispatchCallAction(
            snapshot: CallSnapshot,
            action: Notification.Action,
            label: String,
            kind: CallActionKind,
        ): Boolean {
            if (!claimCallAction(snapshot, kind, label)) return false
            val sent = sendActionPendingIntent(action, label)
            if (!sent) clearPendingCallAction(snapshot.key, kind)
            return sent
        }

        private fun claimCallAction(snapshot: CallSnapshot, kind: CallActionKind, label: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            synchronized(snapshotLock) {
                if (callSnapshots[snapshot.key] != snapshot || currentCallSnapshot() != snapshot) {
                    Log.d(TAG, "$label: call snapshot changed before dispatch")
                    return false
                }
                val pending = pendingCallAction
                if (pending != null && pending.key == snapshot.key && pending.stage == snapshot.stage &&
                    now - pending.startedAtElapsedMs < CALL_ACTION_PENDING_WINDOW_MS &&
                    (pending.kind == kind || pending.kind == CallActionKind.TELECOM_ANSWER)
                ) {
                    Log.d(TAG, "$label: matching call action is still pending for ${snapshot.key}")
                    return false
                }
                pendingCallAction = PendingCallAction(
                    key = snapshot.key,
                    stage = snapshot.stage,
                    kind = kind,
                    actionIntent = snapshot.intentFor(kind),
                    startedAtElapsedMs = now,
                )
            }
            return true
        }

        private fun clearPendingCallAction(key: String, kind: CallActionKind) {
            synchronized(snapshotLock) {
                if (pendingCallAction?.key == key && pendingCallAction?.kind == kind) {
                    pendingCallAction = null
                }
            }
        }

        private fun sendActionPendingIntent(
            action: Notification.Action,
            label: String,
        ): Boolean = try {
            val ctx = notificationListenerContext ?: ServiceManager.getService()?.applicationContext
            val fillIn = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val optsBundle = if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 36) {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        } else {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        }
                    )
                    .toBundle()
            } else null
            action.actionIntent.send(ctx, 0, fillIn, null, null, null, optsBundle)
            Log.d(TAG, "$label: dispatched ${action.title}; awaiting call-state transition")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$label failed: ${t.message}")
            false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        notificationListenerContext = applicationContext
        io.automated.ventures.everypods.utils.MediaController.refreshMediaSessionMonitoring(this, force = true)
        Log.d(TAG, "Listener connected")
        // Re-scan currently posted notifications so we pick up an in-progress call.
        clearSnapshots()
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "scan active notifications failed: ${t.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onListenerDisconnected() {
        clearSnapshots()
        notificationListenerContext = null
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        Log.d(TAG, "Call notification removed; releasing snapshot for ${sbn.key}")
        publishSnapshot(sbn.key, null)
    }

    override fun onDestroy() {
        clearSnapshots()
        notificationListenerContext = null
        super.onDestroy()
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

    /** Read Teams' own notification strings, never guessed translations. Android
     * falls back to Teams' default resources for languages it has not installed. */
    private fun teamsMuteLabels(packageName: String): Map<String, Boolean> {
        if (!packageName.startsWith("com.microsoft.teams")) return emptyMap()
        return try {
            val teams = createPackageContext(packageName, 0)
            val tags = resources.getStringArray(R.array.supported_locale_tags)
            readTeamsMuteLabels(teams, packageName, tags)
        } catch (e: Exception) {
            Log.d(TAG, "Teams notification label resources unavailable: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    /** Read the exact Teams resource labels across EveryPods' supported locales. */
    private fun teamsCallLabels(packageName: String): Map<String, String> {
        if (!packageName.startsWith("com.microsoft.teams")) return emptyMap()
        return try {
            val teams = createPackageContext(packageName, 0)
            val tags = resources.getStringArray(R.array.supported_locale_tags)
            readTeamsCallLabels(teams, packageName, tags)
        } catch (e: Exception) {
            Log.d(TAG, "Teams call action resources unavailable: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    private fun matchesActionLabel(title: String, keyword: String): Boolean {
        val normalizedTitle = title.replace(ACTION_WORD_SEPARATORS, " ").trim()
        val normalizedKeyword = keyword.replace(ACTION_WORD_SEPARATORS, " ").trim()
        return normalizedKeyword.isNotEmpty() && " $normalizedTitle ".contains(" $normalizedKeyword ")
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in WATCHED_PACKAGES) return
        notificationListenerContext = applicationContext
        val n = sbn.notification ?: return
        val actions: Array<Notification.Action> = n.actions ?: emptyArray()

        // Verbose log for debugging.
        val titles = actions.map { it.title?.toString().orEmpty() }
        Log.d(TAG, "Scanning ${sbn.packageName} notification (key=${sbn.key}, actions=${titles})")

        // ─── Structural CallStyle state and actions ───────────────────────────
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

        // ─── Fallback action metadata and whole-label matching ────────────────
        // These clues can fill missing actions, but cannot override CallStyle
        // intents already resolved above or determine the CallStyle stage.
        val teamsLabels = teamsMuteLabels(sbn.packageName)
        val teamsCalls = teamsCallLabels(sbn.packageName)
        for (a in actions) {
            val iconName = if (sbn.packageName.startsWith("com.microsoft.teams")) {
                try {
                    val icon = a.getIcon()
                    if (icon?.type == Icon.TYPE_RESOURCE) {
                        packageManager.getResourcesForApplication(sbn.packageName)
                            .getResourceEntryName(icon.resId)
                    } else null
                } catch (_: Exception) { null }
            } else null
            val metadataState = muteStateForAction(a.semanticAction, iconName)
            Log.d(TAG, "Call action metadata: semantic=${a.semanticAction} icon=$iconName muted=$metadataState")
            if (metadataState != null) {
                if (metadataState && foundUnmute == null) foundUnmute = a
                else if (!metadataState && foundMute == null) foundMute = a
                continue
            }
            if (iconName == "icn_callaction_endcall") {
                if (foundHangUp == null) foundHangUp = a
                continue
            }
            val rawTitle = a.title?.toString().orEmpty()
            val title = rawTitle.lowercase(Locale.ROOT)
            if (title.isEmpty()) continue
            val normalizedTitle = normalizeActionLabel(rawTitle)
            when (teamsCalls[normalizedTitle]) {
                "answer" -> if (foundAnswer == null) foundAnswer = a
                "decline" -> if (foundDecline == null) foundDecline = a
            }
            val resourceState = teamsLabels[normalizedTitle]
            if (resourceState != null) {
                Log.d(TAG, "Call action matched Teams string resource: muted=$resourceState")
                if (resourceState && foundUnmute == null) foundUnmute = a
                else if (!resourceState && foundMute == null) foundMute = a
                continue
            }
            // Whole-token matching avoids substring collisions such as the
            // Portuguese answer label "Atender" being mistaken for "end".
            when {
                foundUnmute == null && UNMUTE_KEYWORDS.any { matchesActionLabel(title, it) }  -> foundUnmute  = a
                foundMute   == null && MUTE_KEYWORDS.any   { matchesActionLabel(title, it) }  -> foundMute    = a
                foundHangUp == null && HANG_UP_KEYWORDS.any{ matchesActionLabel(title, it) }  -> foundHangUp  = a
                foundAnswer == null && ANSWER_KEYWORDS.any { matchesActionLabel(title, it) }  -> foundAnswer  = a
                foundDecline== null && DECLINE_KEYWORDS.any{ matchesActionLabel(title, it) }  -> foundDecline = a
            }
        }

        val stage = when (callTypeRaw) {
            Notification.CallStyle.CALL_TYPE_INCOMING -> CallStage.INCOMING
            Notification.CallStyle.CALL_TYPE_ONGOING -> CallStage.ONGOING
            Notification.CallStyle.CALL_TYPE_SCREENING -> CallStage.SCREENING
            else -> when {
                n.category != Notification.CATEGORY_CALL -> null
                foundAnswer != null && foundDecline != null -> CallStage.INCOMING
                foundHangUp != null || foundMute != null || foundUnmute != null -> CallStage.ONGOING
                else -> CallStage.UNKNOWN
            }
        }

        if (stage == null) {
            // If an app reuses a key for a non-call notification, drop the old
            // call/actions owned by that key instead of retaining stale state.
            publishSnapshot(sbn.key, null)
            return
        }

        val snapshot = CallSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            stage = stage,
            mute = foundMute,
            unmute = foundUnmute,
            hangUp = foundHangUp,
            answer = foundAnswer,
            decline = foundDecline,
        )
        publishSnapshot(sbn.key, snapshot)
        Log.d(
            TAG,
            "Replaced call snapshot key=${sbn.key} package=${sbn.packageName} stage=$stage " +
                "actions(mute=${foundMute != null}, unmute=${foundUnmute != null}, " +
                "hangUp=${foundHangUp != null}, answer=${foundAnswer != null}, decline=${foundDecline != null})"
        )

        // Self-managed VoIP ringing may not trigger TelephonyManager call state,
        // so reapply the stem configuration from the notification stage itself.
        if (stage == CallStage.INCOMING) {
            Log.d(TAG, "Incoming VoIP notification detected for ${sbn.packageName} — setting up stem actions")
            ServiceManager.getService()?.setupStemActions()
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
