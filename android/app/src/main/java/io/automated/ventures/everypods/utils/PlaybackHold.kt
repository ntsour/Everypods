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

package io.automated.ventures.everypods.utils

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.automated.ventures.everypods.services.ServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phases of a playback hold. See docs/playback-hold.md. */
enum class HoldPhase { REQUESTING_RELEASE, CONNECTING, ROUTING, CONNECTED, FAILED }

/**
 * MEDIA holds the user's playback while the AirPods connect. CALL only reports the
 * handover for a call: no focus, no mute, nothing paused (docs/playback-hold.md).
 */
enum class HoldKind { MEDIA, CALL }

data class HoldStatus(
    val phase: HoldPhase,
    val deviceName: String,
    /** Name of the peer we asked to release, for REQUESTING_RELEASE text. */
    val peerName: String?,
    /** 1-based attempt counter; incremented by [PlaybackHold.retry]. */
    val attempt: Int,
    val phaseStartedAtMs: Long,
    val attemptStartedAtMs: Long,
    val attemptTimeoutMs: Long,
    val kind: HoldKind = HoldKind.MEDIA,
)

/**
 * Holds the user's media (via transient audio focus) while the AirPods connect,
 * so nothing plays from the phone speaker and playback starts once on the AirPods.
 *
 * Shared contract between the hold logic and the overlay UI
 * (presentation/overlays/HoldOverlayController.kt). The UI only reads [status]
 * and calls [retry], [playOnPhone] and [dismissStayPaused].
 */
object PlaybackHold {
    private const val TAG = "PlaybackHold"

    /** Per-attempt budget before the hold goes FAILED. */
    private const val ATTEMPT_TIMEOUT_MS = 10_000L
    /** How long FAILED waits for a Retry / Play on phone choice before staying paused. */
    private const val FAILED_CHOICE_TIMEOUT_MS = 12_000L
    /** Hard cap on the STREAM_MUSIC mute, whatever happens. */
    private const val MUTE_HARD_CAP_MS = 1_500L
    private const val MUTE_POLL_MS = 100L
    private const val ROUTE_POLL_MS = 150L
    private const val ROUTE_POLL_MAX_MS = 3_000L
    private const val SAFETY_NET_DELAY_MS = 800L
    /** How long CONNECTED stays published so the overlay can show it. */
    private const val CONNECTED_LINGER_MS = 1_500L
    /** After the user takes playback back, don't start a new hold for this long. */
    private const val USER_CANCEL_SUPPRESS_MS = 5_000L
    /** Delay between our pause key and abandoning focus on stay-paused. */
    private const val PAUSE_BEFORE_ABANDON_MS = 250L
    private const val PREF_MUTED = "playback_hold_muted"

    private val _status = MutableStateFlow<HoldStatus?>(null)

    /** Current hold, or null when no hold is active. Emitted on the main thread. */
    val status: StateFlow<HoldStatus?> = _status.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var prefs: SharedPreferences? = null

    // All of the below is touched on the main thread only, except the @Volatile ones.
    @Volatile private var active = false
    @Volatile private var releasedAtUptime = 0L
    private var mac: String = ""
    private var current: HoldStatus? = null
    private var focusRequest: AudioFocusRequest? = null
    private var weMuted = false
    private var routeVerifyStartedAt = 0L
    private var suppressUntil = 0L
    private var holdToken = 0
    @Volatile private var kind = HoldKind.MEDIA

    /** True from [start] / [startCall] until the hold is released or cancelled. */
    val isActive: Boolean get() = active

    /** True only while media is actually being held (not for call status). */
    val isMediaHold: Boolean get() = active && kind == HoldKind.MEDIA

    /** True within [windowMs] after a hold released (success, play on phone or stay paused). */
    fun releasedRecently(windowMs: Long): Boolean =
        releasedAtUptime != 0L && SystemClock.uptimeMillis() - releasedAtUptime < windowMs

    // ── Public API (UI) ───────────────────────────────────────────────────────

    /** FAILED → try connecting again, keeping the hold. */
    fun retry() = onMain {
        val s = current
        if (!active || s == null || s.phase != HoldPhase.FAILED) {
            Log.d(TAG, "retry ignored (active=$active phase=${s?.phase})")
            return@onMain
        }
        Log.d(TAG, "retry: attempt ${s.attempt + 1}")
        handler.removeCallbacks(failedChoiceTimeout)
        val now = SystemClock.uptimeMillis()
        val phase = if (CrossDevice.isPeerConnected) HoldPhase.REQUESTING_RELEASE else HoldPhase.CONNECTING
        current = s.copy(
            phase = phase, attempt = s.attempt + 1,
            phaseStartedAtMs = now, attemptStartedAtMs = now, attemptTimeoutMs = ATTEMPT_TIMEOUT_MS,
        )
        publish(current)
        armAttemptTimeout()
        ServiceManager.getService()?.takeOver(if (kind == HoldKind.CALL) "call" else "music")
    }

    /** FAILED (or any phase) → give up on the AirPods and resume on the speaker. */
    fun playOnPhone() = onMain {
        if (!active) return@onMain
        if (kind == HoldKind.CALL) { endCall("use phone"); return@onMain }
        Log.d(TAG, "playOnPhone: releasing hold, app resumes on speaker")
        MediaController.cancelPendingMusicTakeover()
        release(replaySafetyNet = true)
        clearStatusNow()
    }

    /** FAILED → end the hold but leave the media paused. */
    fun dismissStayPaused() = onMain { endStayPaused("dismissStayPaused") }

    /**
     * Overall progress in [0, 1] for a progress ring/bar. Time-based within phase
     * bands so it always moves: REQUESTING_RELEASE 0–0.3, CONNECTING 0.3–0.7,
     * ROUTING 0.7–0.95, CONNECTED 1.0. FAILED keeps the last band's end (0.95).
     */
    fun progressFraction(s: HoldStatus, nowMs: Long): Float {
        val (start, end) = when (s.phase) {
            HoldPhase.REQUESTING_RELEASE -> 0.0f to 0.3f
            HoldPhase.CONNECTING -> 0.3f to 0.7f
            HoldPhase.ROUTING -> 0.7f to 0.95f
            HoldPhase.CONNECTED -> return 1f
            HoldPhase.FAILED -> return 0.95f
        }
        // Ease toward the band's end over the remaining attempt budget.
        val remaining = (s.attemptStartedAtMs + s.attemptTimeoutMs - s.phaseStartedAtMs).coerceAtLeast(1L)
        val t = ((nowMs - s.phaseStartedAtMs).toFloat() / remaining).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t)
        return start + (end - start) * eased
    }

    /** For the hold logic only. */
    internal fun publish(status: HoldStatus?) {
        _status.value = status
    }

    // ── Hooks for AirPodsService / MediaController ────────────────────────────

    /** Service onCreate. Also recovers from a crash that left STREAM_MUSIC muted. */
    fun init(context: Context, audioManager: AudioManager, prefs: SharedPreferences) = onMain {
        this.audioManager = audioManager
        this.prefs = prefs
        if (prefs.getBoolean(PREF_MUTED, false)) {
            Log.w(TAG, "init: found stale ${PREF_MUTED}=true (crash while muted), unmuting")
            runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            prefs.edit().putBoolean(PREF_MUTED, false).apply()
        }
    }

    /**
     * takeOver("music") passed all gates and is about to connectAudio(). No-op if a hold
     * is already active (retry path) or the user just took playback back.
     */
    fun start(deviceMac: String, deviceName: String, peerName: String?) = onMain {
        val am = audioManager
        if (am == null) { Log.w(TAG, "start: not initialised"); return@onMain }
        if (active) { Log.d(TAG, "start: already active (kind=$kind), keeping it"); return@onMain }
        val now = SystemClock.uptimeMillis()
        if (now < suppressUntil) {
            Log.d(TAG, "start: suppressed (user took playback back ${suppressUntil - now}ms ago window)")
            return@onMain
        }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        // Our own focus grab makes the app pause; don't let MediaController read that
        // as a user pause/play.
        MediaController.markSelfAction()
        val result = runCatching { am.requestAudioFocus(req) }.getOrNull()
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "start: audio focus not granted (result=$result), not holding")
            return@onMain
        }
        focusRequest = req
        mac = deviceMac
        kind = HoldKind.MEDIA
        active = true
        releasedAtUptime = 0L
        holdToken++
        val phase = if (CrossDevice.isPeerConnected) HoldPhase.REQUESTING_RELEASE else HoldPhase.CONNECTING
        current = HoldStatus(
            phase = phase, deviceName = deviceName, peerName = peerName, attempt = 1,
            phaseStartedAtMs = now, attemptStartedAtMs = now, attemptTimeoutMs = ATTEMPT_TIMEOUT_MS,
        )
        Log.d(TAG, "start: holding for $deviceName ($deviceMac), phase=$phase, peer=$peerName")
        publish(current)
        armAttemptTimeout()
        startMute(am)
    }

    /** A2DP profile reported STATE_CONNECTING for our MAC. */
    private var headsetConnected = false

    fun onA2dpConnecting(deviceMac: String) = onMain {
        if (!active || !deviceMac.equals(mac, true)) return@onMain
        if (current?.phase == HoldPhase.REQUESTING_RELEASE) {
            Log.d(TAG, "A2DP connecting → CONNECTING")
            setPhase(HoldPhase.CONNECTING)
        }
    }

    /** A2DP profile reported STATE_CONNECTED for our MAC. */
    fun onA2dpConnected(deviceMac: String) = onMain {
        if (!active || !deviceMac.equals(mac, true)) return@onMain
        if (kind == HoldKind.CALL) {
            if (current?.phase == HoldPhase.REQUESTING_RELEASE) setPhase(HoldPhase.CONNECTING)
            return@onMain
        }
        Log.d(TAG, "A2DP connected → ROUTING")
        enterRouting()
    }

    /** AudioDeviceCallback added our A2DP MAC. */
    fun onAudioDeviceAdded(deviceMac: String) = onMain {
        if (!active || kind != HoldKind.MEDIA || !deviceMac.equals(mac, true)) return@onMain
        Log.d(TAG, "audio device added → ROUTING")
        enterRouting()
    }

    /**
     * takeOver("call") is about to connect: show handover progress for the call. Nothing
     * is held or muted. A media hold in progress ends with the media left paused.
     */
    fun startCall(deviceMac: String, deviceName: String) = onMain {
        if (active && kind == HoldKind.CALL) { Log.d(TAG, "startCall: already showing call status"); return@onMain }
        if (active) endStayPaused("call takes over")
        val now = SystemClock.uptimeMillis()
        mac = deviceMac
        kind = HoldKind.CALL
        headsetConnected = false
        active = true
        holdToken++
        val phase = if (CrossDevice.isPeerConnected) HoldPhase.REQUESTING_RELEASE else HoldPhase.CONNECTING
        current = HoldStatus(
            phase = phase, deviceName = deviceName, peerName = null, attempt = 1,
            phaseStartedAtMs = now, attemptStartedAtMs = now, attemptTimeoutMs = ATTEMPT_TIMEOUT_MS,
            kind = HoldKind.CALL,
        )
        Log.d(TAG, "startCall: call status for $deviceName ($deviceMac), phase=$phase")
        publish(current)
        armAttemptTimeout()
    }

    /** Headset (HFP) profile state for our MAC: drives call status phases. */
    fun onHeadsetState(deviceMac: String, state: Int) = onMain {
        if (!active || kind != HoldKind.CALL || !deviceMac.equals(mac, true)) return@onMain
        when (state) {
            android.bluetooth.BluetoothProfile.STATE_CONNECTING ->
                if (current?.phase == HoldPhase.REQUESTING_RELEASE) setPhase(HoldPhase.CONNECTING)
            android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                Log.d(TAG, "call: headset connected → ROUTING")
                headsetConnected = true
                enterRouting()
            }
        }
    }

    /** The call ended (cellular IDLE or VoIP left IN_COMMUNICATION): drop call status. */
    fun onCallEnded() = onMain {
        if (active && kind == HoldKind.CALL) endCall("call ended")
    }

    /** AirPods were taken by a peer: end the hold and stay paused. */
    fun onOwnershipLost() = onMain { endStayPaused("ownership lost") }

    /** Service destroyed: abandon focus and unmute. Nothing else. */
    fun shutdown() = onMain {
        Log.d(TAG, "shutdown (active=$active)")
        cancelAllTimers()
        unmute("shutdown")
        abandonFocus()
        active = false
        current = null
        _status.value = null
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onMain {
            if (!active || kind != HoldKind.MEDIA) return@onMain
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Another app (almost certainly the one we paused: the user pressed
                    // play in it) took focus permanently. End the hold, connection continues.
                    Log.d(TAG, "AUDIOFOCUS_LOSS during hold: user took playback back, ending hold")
                    suppressUntil = SystemClock.uptimeMillis() + USER_CANCEL_SUPPRESS_MS
                    release(replaySafetyNet = false, abandon = false, userTookBack = true)
                    clearStatusNow()
                }
                else -> Log.d(TAG, "focus change $change ignored during hold")
            }
        }
    }

    private fun setPhase(phase: HoldPhase) {
        val s = current ?: return
        if (s.phase == phase) return
        current = s.copy(phase = phase, phaseStartedAtMs = SystemClock.uptimeMillis())
        publish(current)
    }

    private fun enterRouting() {
        val s = current ?: return
        if (s.phase == HoldPhase.ROUTING || s.phase == HoldPhase.CONNECTED) return
        if (s.phase == HoldPhase.FAILED) {
            // Late success while still holding: stop the stay-paused countdown, give
            // route verification a fresh budget.
            handler.removeCallbacks(failedChoiceTimeout)
            val now = SystemClock.uptimeMillis()
            current = s.copy(attemptStartedAtMs = now)
            armAttemptTimeout()
        }
        setPhase(HoldPhase.ROUTING)
        routeVerifyStartedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(routePoll)
        handler.post(routePoll)
    }

    private val routePoll = object : Runnable {
        override fun run() {
            if (!active || current?.phase != HoldPhase.ROUTING) return
            val elapsed = SystemClock.uptimeMillis() - routeVerifyStartedAt
            if (isMediaOnOurDevice()) {
                Log.d(TAG, "route verified after ${elapsed}ms")
                connected()
            } else if (elapsed >= ROUTE_POLL_MAX_MS) {
                Log.w(TAG, "route not verified after ${elapsed}ms, accepting anyway")
                connected()
            } else {
                handler.postDelayed(this, ROUTE_POLL_MS)
            }
        }
    }

    private fun isMediaOnOurDevice(): Boolean {
        val am = audioManager ?: return false
        if (kind == HoldKind.CALL) {
            // Call audio: HFP up (in-band ring) or the AirPods are the communication device.
            if (headsetConnected) return true
            return runCatching { am.communicationDevice?.address.equals(mac, true) }.getOrDefault(false)
        }
        return try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(mac, true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getAudioDevicesForAttributes failed: ${t.message}")
            false
        }
    }

    private fun connected() {
        handler.removeCallbacks(routePoll)
        handler.removeCallbacks(attemptTimeout)
        handler.removeCallbacks(failedChoiceTimeout)
        setPhase(HoldPhase.CONNECTED)
        if (kind == HoldKind.CALL) {
            active = false
            Log.d(TAG, "call: audio on AirPods")
        } else {
            release(replaySafetyNet = true)
        }
        // Leave CONNECTED published briefly so the overlay can show it, then clear —
        // unless a newer hold has taken over the status in the meantime.
        val token = holdToken
        handler.postDelayed({
            if (!active && token == holdToken && current?.phase == HoldPhase.CONNECTED) clearStatusNow()
        }, CONNECTED_LINGER_MS)
    }

    private val attemptTimeout = Runnable {
        if (!active) return@Runnable
        val s = current ?: return@Runnable
        if (s.phase == HoldPhase.CONNECTED || s.phase == HoldPhase.FAILED) return@Runnable
        Log.w(TAG, "attempt ${s.attempt} timed out in ${s.phase} → FAILED (still holding)")
        handler.removeCallbacks(routePoll)
        // FAILED is a hold-ending path for the mute, too.
        unmute("attempt failed")
        setPhase(HoldPhase.FAILED)
        handler.postDelayed(failedChoiceTimeout, FAILED_CHOICE_TIMEOUT_MS)
    }

    private val failedChoiceTimeout = Runnable {
        if (active && current?.phase == HoldPhase.FAILED) {
            Log.d(TAG, "no choice within ${FAILED_CHOICE_TIMEOUT_MS}ms → stay paused")
            endStayPaused("failed choice timeout")
        }
    }

    private fun armAttemptTimeout() {
        handler.removeCallbacks(attemptTimeout)
        val s = current ?: return
        val left = (s.attemptStartedAtMs + s.attemptTimeoutMs - SystemClock.uptimeMillis()).coerceAtLeast(1L)
        handler.postDelayed(attemptTimeout, left)
    }

    /**
     * End the hold leaving the media paused: pause key first (paused apps still take it,
     * clearing e.g. Media3 playWhenReady), then abandon focus so the app can't resume.
     */
    private fun endStayPaused(reason: String) {
        if (!active) return
        if (kind == HoldKind.CALL) { endCall(reason); return }
        Log.d(TAG, "stay paused: $reason")
        MediaController.cancelPendingMusicTakeover()
        cancelAllTimers()
        unmute("stay paused")
        val am = audioManager
        MediaController.markSelfAction()
        if (am != null) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        active = false
        releasedAtUptime = SystemClock.uptimeMillis()
        clearStatusNow()
        handler.postDelayed({
            MediaController.markSelfAction()
            abandonFocus()
        }, PAUSE_BEFORE_ABANDON_MS)
    }

    /** End call status: nothing was held, so just stop timers and clear the overlay. */
    private fun endCall(reason: String) {
        Log.d(TAG, "call status ended: $reason")
        cancelAllTimers()
        active = false
        headsetConnected = false
        kind = HoldKind.MEDIA
        clearStatusNow()
    }

    /**
     * Release the hold: unmute (always before abandoning), abandon focus, stamp self-action
     * and optionally schedule the replay safety net.
     */
    private fun release(replaySafetyNet: Boolean, abandon: Boolean = true, userTookBack: Boolean = false) {
        cancelAllTimers()
        unmute("release")
        MediaController.markSelfAction()
        if (abandon) abandonFocus() else focusRequest = null
        active = false
        if (!userTookBack) {
            // The hold owned the release, so drop the pending takeover: otherwise a later
            // route blip would run MediaController's old replay path. When the user took
            // playback back, leave it so the connection finishes "as today".
            releasedAtUptime = SystemClock.uptimeMillis()
            MediaController.cancelPendingMusicTakeover()
        }
        Log.d(TAG, "released (safetyNet=$replaySafetyNet)")
        if (replaySafetyNet) {
            handler.postDelayed({
                val am = audioManager ?: return@postDelayed
                if (!am.isMusicActive) {
                    Log.d(TAG, "safety net: music not active ${SAFETY_NET_DELAY_MS}ms after release → sendPlay(force)")
                    MediaController.sendPlay(force = true, routeToRelay = false)
                } else {
                    Log.d(TAG, "safety net: music resumed by itself")
                }
            }, SAFETY_NET_DELAY_MS)
        }
    }

    private fun cancelAllTimers() {
        handler.removeCallbacks(attemptTimeout)
        handler.removeCallbacks(failedChoiceTimeout)
        handler.removeCallbacks(routePoll)
        handler.removeCallbacks(mutePoll)
        handler.removeCallbacks(muteHardCap)
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(req) }
            .onFailure { Log.w(TAG, "abandonAudioFocusRequest failed: ${it.message}") }
    }

    private fun clearStatusNow() {
        current = null
        _status.value = null
    }

    // ── Mute ──────────────────────────────────────────────────────────────────

    private fun startMute(am: AudioManager) {
        if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            Log.d(TAG, "STREAM_MUSIC already muted, not touching it")
            return
        }
        // Persist BEFORE muting so a crash in between still gets recovered on next init.
        prefs?.edit()?.putBoolean(PREF_MUTED, true)?.commit()
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
        weMuted = true
        Log.d(TAG, "STREAM_MUSIC muted")
        handler.postDelayed(muteHardCap, MUTE_HARD_CAP_MS)
        handler.postDelayed(mutePoll, MUTE_POLL_MS)
    }

    /** Unmute as soon as the app has actually paused. */
    private val mutePoll = object : Runnable {
        override fun run() {
            if (!weMuted) return
            if (audioManager?.isMusicActive == false) unmute("app paused")
            else handler.postDelayed(this, MUTE_POLL_MS)
        }
    }

    private val muteHardCap = Runnable { unmute("1.5s hard cap") }

    private fun unmute(reason: String) {
        handler.removeCallbacks(mutePoll)
        handler.removeCallbacks(muteHardCap)
        if (!weMuted) return
        weMuted = false
        runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        prefs?.edit()?.putBoolean(PREF_MUTED, false)?.apply()
        Log.d(TAG, "STREAM_MUSIC unmuted ($reason)")
    }
}
