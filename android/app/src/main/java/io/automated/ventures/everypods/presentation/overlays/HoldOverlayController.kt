/*
    EveryPods - AirPods liberated from Apple’s ecosystem
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


package io.automated.ventures.everypods.presentation.overlays

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.services.AirPodsService
import io.automated.ventures.everypods.utils.HoldKind
import io.automated.ventures.everypods.utils.HoldPhase
import io.automated.ventures.everypods.utils.HoldStatus
import io.automated.ventures.everypods.utils.PlaybackHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Drives the overlay (island, bottom sheet or notification) for [PlaybackHold].
 * Started and stopped by AirPodsService. See docs/playback-hold.md.
 * All state is touched on the main thread.
 */
object HoldOverlayController {
    private const val TAG = "HoldOverlay"
    private const val CHANNEL_ID = "playback_hold"
    private const val NOTIFICATION_ID = 7421
    private const val ACTION_RETRY = "io.automated.ventures.everypods.action.HOLD_RETRY"
    private const val ACTION_PLAY_ON_PHONE = "io.automated.ventures.everypods.action.HOLD_PLAY_ON_PHONE"
    private const val TICK_MS = 100L

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var service: AirPodsService? = null
    private var receiverRegistered = false

    private var island: IslandWindow? = null
    private var popup: PopupWindow? = null
    private var notificationShown = false
    /** Phase the notification currently shows (null = none); it is only re-posted on a phase change. */
    private var notifiedPhase: HoldPhase? = null

    /** User swiped / closed the overlay for this hold; don't re-show until FAILED. */
    private var dismissed = false
    private var lastPhase: HoldPhase? = null
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            val s = PlaybackHold.status.value ?: return
            render(s)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RETRY -> PlaybackHold.retry()
                ACTION_PLAY_ON_PHONE -> PlaybackHold.playOnPhone()
            }
        }
    }

    fun start(service: AirPodsService) {
        main.post { startOnMain(service) }
    }

    fun stop() {
        main.post { stopOnMain() }
    }

    private fun startOnMain(svc: AirPodsService) {
        stopOnMain()
        service = svc
        val filter = IntentFilter().apply {
            addAction(ACTION_RETRY)
            addAction(ACTION_PLAY_ON_PHONE)
        }
        try {
            ContextCompat.registerReceiver(svc, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        job = newScope.launch {
            PlaybackHold.status.collect { render(it) }
        }
    }

    private fun stopOnMain() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        main.removeCallbacks(tick)
        ticking = false
        hideAll()
        cancelNotification()
        dismissed = false
        lastPhase = null
        if (receiverRegistered) {
            try {
                service?.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver failed: ${e.message}")
            }
            receiverRegistered = false
        }
        service = null
    }

    private fun hideAll() {
        island?.let { w -> island = null; if (w.isHolding) w.close() }
        popup?.let { w -> popup = null; if (w.isHolding) w.close() }
    }

    private fun render(status: HoldStatus?) {
        val svc = service ?: return
        if (status == null) {
            main.removeCallbacks(tick)
            ticking = false
            hideAll()
            cancelNotification()
            dismissed = false
            lastPhase = null
            return
        }
        if (status.phase == HoldPhase.FAILED && lastPhase != HoldPhase.FAILED) dismissed = false
        lastPhase = status.phase

        val prefs = svc.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val canOverlay = Settings.canDrawOverlays(svc)
        val progress = PlaybackHold.progressFraction(status, SystemClock.uptimeMillis())

        val isCall = status.kind == HoldKind.CALL
        val locked = (svc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
        // Calls: a ringing call on an unlocked phone shows a heads-up banner at the top, so the
        // bottom popup is used then. Otherwise (lock screen, or the full in-call screen) the
        // Answer / call controls are at the bottom, so the top island is used.
        // The takeover starts on the RINGING phone state, before Telecom switches the audio mode
        // (26 Nov: the mode turned RINGTONE 60 ms after this check, so a ringing call got the island
        // under the incoming-call banner). The phone and VoIP ring flags are set first.
        val ringing = svc.callRinging ||
            (svc.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode == AudioManager.MODE_RINGTONE
        val callAtBottom = ringing && !locked
        when {
            island != null -> renderIsland(status, progress)
            popup != null -> renderPopup(status, progress)
            dismissed -> Unit
            isCall && canShowOverlay(prefs, canOverlay) && callAtBottom -> {
                showPopup(svc, prefs.getString("name", "AirPods Pro").toString())
                renderPopup(status, progress)
            }
            isCall && canShowOverlay(prefs, canOverlay) -> {
                showIsland(svc, prefs.getString("name", "AirPods Pro").toString(), status)
                renderIsland(status, progress)
            }
            canOverlay && prefs.getBoolean("show_island_popup", true) -> {
                showIsland(svc, prefs.getString("name", "AirPods Pro").toString(), status)
                renderIsland(status, progress)
            }
            canOverlay && prefs.getBoolean("show_bottom_sheet_popup", false) -> {
                showPopup(svc, prefs.getString("name", "AirPods Pro").toString())
                renderPopup(status, progress)
            }
            else -> Unit
        }

        // Notification: progress and failure while locked (the lock screen may not draw the
        // overlay); otherwise only a failure when no overlay is in use.
        val noOverlay = island == null && popup == null
        when {
            locked && !dismissed -> postNotification(svc, status)
            noOverlay && !canShowOverlay(prefs, canOverlay) && status.phase == HoldPhase.FAILED && !dismissed ->
                postNotification(svc, status)
            else -> cancelNotification()
        }

        val animating = status.phase != HoldPhase.FAILED && status.phase != HoldPhase.CONNECTED
        if (animating && !ticking && (island != null || popup != null)) {
            ticking = true
            main.postDelayed(tick, TICK_MS)
        }
    }

    private fun canShowOverlay(prefs: android.content.SharedPreferences, canOverlay: Boolean): Boolean =
        canOverlay && (prefs.getBoolean("show_island_popup", true) || prefs.getBoolean("show_bottom_sheet_popup", false))

    private fun showIsland(svc: AirPodsService, name: String, status: HoldStatus) {
        val type = if (status.phase == HoldPhase.FAILED) IslandType.FAILED else IslandType.CONNECTING
        // IslandWindow.show() is a no-op while another island is open, which would leave the
        // hold with no UI (and no Retry / Play on phone on failure). Replace the open one.
        if (svc.islandOpen) {
            svc.islandWindow?.forceClose()
            svc.islandOpen = false
        }
        val w = IslandWindow(svc.applicationContext)
        island = w
        svc.islandWindow = w
        w.onClosed = { onWindowClosed(w) }
        try {
            w.show(name, 0, svc, type)
        } catch (e: Exception) {
            Log.w(TAG, "island show failed: ${e.message}")
        }
    }

    private fun renderIsland(status: HoldStatus, progress: Float) {
        island?.updateHold(status, progress)
    }

    private fun showPopup(svc: AirPodsService, name: String) {
        val ref = arrayOfNulls<PopupWindow>(1)
        val w = PopupWindow(svc.applicationContext) { ref[0]?.let { onWindowClosed(it) } }
        ref[0] = w
        popup = w
        try {
            w.open(name, svc.batteryNotification, holdMode = true)
        } catch (e: Exception) {
            Log.w(TAG, "popup open failed: ${e.message}")
        }
    }

    private fun renderPopup(status: HoldStatus, progress: Float) {
        popup?.updateHold(status, progress)
    }

    private fun onWindowClosed(w: Any) {
        if (island === w) island = null else if (popup === w) popup = null else return
        val s = PlaybackHold.status.value ?: return
        if (s.phase == HoldPhase.CONNECTED) return
        dismissed = true
        if (s.phase == HoldPhase.FAILED) PlaybackHold.dismissStayPaused()
    }

    // ── Notification fallback ────────────────────────────────────────────────

    private fun postNotification(svc: AirPodsService, status: HoldStatus) {
        if (notificationShown && notifiedPhase == status.phase) return
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            svc.getString(R.string.hold_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = svc.getString(R.string.hold_notification_channel_description) }
        nm.createNotificationChannel(channel)

        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            svc, code,
            Intent(name).setPackage(svc.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isCall = status.kind == HoldKind.CALL
        val b = NotificationCompat.Builder(svc, CHANNEL_ID)
            .setSmallIcon(R.drawable.airpods)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
        if (status.phase == HoldPhase.FAILED) {
            b.setContentTitle(svc.getString(R.string.hold_notification_title, status.deviceName))
                .setContentText(svc.getString(
                    if (isCall) R.string.hold_call_notification_text else R.string.hold_notification_text
                ))
                .setAutoCancel(true)
                .addAction(0, svc.getString(R.string.hold_retry), action(ACTION_RETRY, 1))
                .addAction(0, svc.getString(
                    if (isCall) R.string.hold_use_phone else R.string.hold_play_on_phone
                ), action(ACTION_PLAY_ON_PHONE, 2))
        } else {
            val text = when (status.phase) {
                HoldPhase.REQUESTING_RELEASE ->
                    if (status.peerName.isNullOrEmpty()) svc.getString(R.string.hold_asking_peer_generic)
                    else svc.getString(R.string.hold_asking_peer, status.peerName)
                HoldPhase.CONNECTING -> svc.getString(
                    if (isCall) R.string.hold_call_connecting else R.string.hold_connecting, status.deviceName
                )
                HoldPhase.ROUTING -> svc.getString(
                    if (isCall) R.string.hold_call_switching else R.string.hold_switching_audio
                )
                else -> svc.getString(R.string.hold_connected)
            }
            b.setContentTitle(status.deviceName)
                .setContentText(text)
                .setOngoing(status.phase != HoldPhase.CONNECTED)
            if (status.phase == HoldPhase.CONNECTED) b.setProgress(0, 0, false)
            else b.setProgress(0, 0, true)
        }
        try {
            nm.notify(NOTIFICATION_ID, b.build())
            notificationShown = true
            notifiedPhase = status.phase
            Log.d(TAG, "notification: ${status.kind} ${status.phase}")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    private fun cancelNotification() {
        if (!notificationShown) return
        notificationShown = false
        notifiedPhase = null
        try {
            (service?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel notification failed: ${e.message}")
        }
    }
}
