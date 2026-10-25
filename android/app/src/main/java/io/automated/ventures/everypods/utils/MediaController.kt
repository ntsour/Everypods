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

@file:OptIn(ExperimentalEncodingApi::class)

package io.automated.ventures.everypods.utils

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import io.automated.ventures.everypods.services.ServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    /** True while relay audio is being played locally; it must not trigger media takeover. */
    @Volatile
    private var relayAudioPlaybackActive: Boolean = false

    fun setRelayAudioPlaybackActive(active: Boolean) {
        if (relayAudioPlaybackActive == active) return
        relayAudioPlaybackActive = active
        onRelayHandoverSuppressionChanged(active, "receiver playback")
    }

    private val mutableMediaSessionAccess = MutableStateFlow(false)
    val mediaSessionAccessGranted = mutableMediaSessionAccess.asStateFlow()

    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_COALESCE_MS = 100L
    private val startDetector = MediaStartDetector()
    private var latestPlaybackConfigs: List<AudioPlaybackConfiguration>? = null
    private var playbackCallbackPending = false
    private val processLatestPlayback = Runnable {
        playbackCallbackPending = false
        processPlaybackConfigs(latestPlaybackConfigs)
    }
    private var mediaVerification: Runnable? = null
    private var mediaDetectionGeneration = 0L
    private var lastMediaTakeoverRequestAt: Long? = null
    private var lastKnownIsMusicActive: Boolean? = null

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    // Set to the AirPods MAC every time we call takeOver("music"). The
    // AudioDeviceCallback below fires only when an A2DP device with THIS MAC
    // becomes the audio output — no time-window guessing. Cleared on:
    //   - the expected MAC is added (fire path)
    //   - user explicitly pauses via sendPause (don't fight user intent)
    // Volatile because it's read on the audio-system thread and written on
    // the MediaSession callback thread.
    @Volatile
    private var pendingMusicTakeoverForMac: String? = null

    // Wall-clock time when pendingMusicTakeoverForMac was set. Used to age out
    // stale pending state so a route blip much later can't fire showTakeoverIsland
    // or sendPlay(force=true) against the user.
    @Volatile
    private var pendingMusicTakeoverSetAt: Long = 0L

    /**
     * After lid/case-open passive connect, ignore A2DP PLAYING → routeLandPoller
     * restarts for this long. Keeps lastMusicTakeoverMs intact for ownership.
     */
    private var suppressRouteLandRestartUntilMs: Long = 0L
    private const val PASSIVE_CONNECT_ROUTELAND_SUPPRESS_MS = 20_000L

    // When an A2DP route change lands we watch for the app auto-pausing
    // (Pocket Casts, etc.) and re-issue play immediately. Unlike a single
    // timing guess, this polls the actual playback state with arithmetic
    // backoff (200, 400, 600, ...) and reacts as soon as isMusicActive drops
    // — regardless of exactly when the app decides to pause.
    @Volatile
    private var routeLandWatchPending: Boolean = false
    // Arithmetic-backoff delays: 200, 400, 600 ... 2400 ms → ~15.6 s total.
    private val ROUTE_LAND_DELAYS_MS = longArrayOf(
        200, 400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000, 2200, 2400
    )

    private class RouteLandPoller : Runnable {
        private var checkCount = 0
        private var elapsedMs = 0L

        fun reset() {
            checkCount = 0
            elapsedMs = 0L
        }

        override fun run() {
            if (!routeLandWatchPending) return
            val delay = ROUTE_LAND_DELAYS_MS.getOrElse(checkCount) { 2400 }
            elapsedMs += delay
            checkCount++
            if (elapsedMs > 15_000L) {
                Log.d("MediaController", "routeLandPoller: timed out after ${elapsedMs}ms ($checkCount checks), giving up")
                routeLandWatchPending = false
                reset()
                return
            }
            if (iPausedTheMedia) {
                Log.d("MediaController", "routeLandPoller: user paused, stopping watch")
                routeLandWatchPending = false
                reset()
                return
            }
            if (audioManager.isMusicActive) {
                // Music is currently playing, but on Xiaomi/HyperOS Pocket Casts
                // (and similar apps) auto-pause several seconds AFTER the A2DP
                // stream starts — well past any short "stable" window. Don't
                // declare victory just because music looks fine right now;
                // keep watching until the user pauses or the 15 s budget
                // elapses, so a late auto-pause can still be caught and replayed.
                Log.d("MediaController", "routeLandPoller: music active at ${elapsedMs}ms, continuing watch")
                handler.postDelayed(this, delay)
                return
            }
            // Music dropped — re-issue play (auto-pause from the late route change).
            Log.d("MediaController", "routeLandPoller: music not active at ${elapsedMs}ms (check $checkCount), re-issuing play")
            sendPlay(force = true)
            // Schedule next check with arithmetic backoff.
            handler.postDelayed(this, delay)
        }
    }
    private val routeLandPoller = RouteLandPoller()

    /** EveryPods speech is not music and must never trigger an auto-resume poller. */
    fun cancelRouteRecoveryForAnnouncement() {
        routeLandWatchPending = false
        handler.removeCallbacks(routeLandPoller)
        routeLandPoller.reset()
        Log.d("MediaController", "Speech started; route recovery is suppressed")
    }

    // A cold-connect straight from the case has to page asleep AirPods — the A2DP
    // route can legitimately take 6-12 s to land. 3 s was far too short: the route
    // landed AFTER the window, so onAudioDevicesAdded discarded it as "stale" and
    // never showed the popup or re-issued play, leaving audio stuck on the speaker.
    // 15 s covers slow cold connects; pendingMac is still cleared on pause and on
    // ownership loss, so a genuinely stale entry can't misfire much later.
    private const val PENDING_TAKEOVER_MAX_AGE_MS = 15_000L

    /** Called by AirPodsService.takeOver() right before it actually invokes connectAudio(). */
    fun armPendingMusicTakeover(mac: String) {
        if (mac.isEmpty()) return
        pendingMusicTakeoverForMac = mac
        pendingMusicTakeoverSetAt = System.currentTimeMillis()
        // Clear the "we paused this device's media" flag. By definition, if we're
        // arming a music takeover the user has just initiated playback — any prior
        // iPausedTheMedia=true (e.g. set when we yielded the AirPods to a peer for
        // a call) is stale. Without this, when the AirPods come back the
        // AudioDeviceCallback route-land branch hits the
        // "iPausedTheMedia=true; user paused intentionally, NOT replaying"
        // path and suppresses the replay, leaving the AirPods connected but silent.
        if (iPausedTheMedia) {
            Log.d("MediaController", "armPendingMusicTakeover: clearing stale iPausedTheMedia=true")
            iPausedTheMedia = false
        }
        Log.d("MediaController", "armPendingMusicTakeover($mac)")
    }

    /**
     * Reset the music-active edge-trigger state. Call this whenever the AirPods
     * leave us (peer takeover, autonomous switch, range loss) so the next
     * playback callback after a future play press is treated as a fresh
     * false→true transition instead of being suppressed by a stale `true`.
     */
    fun resetMusicActiveState() {
        if (lastKnownIsMusicActive != null) {
            Log.d("MediaController", "resetMusicActiveState (was: $lastKnownIsMusicActive)")
        }
        lastKnownIsMusicActive = null
        startDetector.rearm(false)
    }

    fun onRelayHandoverSuppressionChanged(suppressed: Boolean, reason: String) {
        val activeAtChange = this::audioManager.isInitialized && audioManager.isMusicActive
        val reset = Runnable {
            cancelMediaVerification()
            handler.removeCallbacks(processLatestPlayback)
            playbackCallbackPending = false
            // Baseline current playback, so Stop itself never triggers a Bluetooth takeover.
            startDetector.rearm(activeAtChange)
            lastKnownIsMusicActive = activeAtChange
            lastMediaTakeoverRequestAt = null
            Log.i("MediaController", "Relay handover suppression=$suppressed reason=$reason; detector baseline active=$activeAtChange")
        }
        if (Looper.myLooper() == handler.looper) reset.run() else handler.post(reset)
    }

    /** Called when we lose ownership or otherwise want to drop the pending state. */
    fun cancelPendingMusicTakeover() {
        if (pendingMusicTakeoverForMac != null) {
            Log.d("MediaController", "cancelPendingMusicTakeover (was: $pendingMusicTakeoverForMac)")
        }
        pendingMusicTakeoverForMac = null
        pendingMusicTakeoverSetAt = 0L
    }

    /**
     * Passive A2DP connect (lid-open / case-open auto-grab) must not resume media.
     * Clears any leftover music-takeover arming and stops the route-land poller so
     * a prior takeOver("music") (up to 60s window) cannot re-issue play when the
     * AirPods reconnect from an open case with no user play press.
     */
    fun clearAutoPlayForPassiveConnect(reason: String) {
        Log.d("MediaController", "clearAutoPlayForPassiveConnect($reason)")
        cancelPendingMusicTakeover()
        if (routeLandWatchPending) {
            Log.d("MediaController", "  → stopping routeLandPoller")
        }
        routeLandWatchPending = false
        handler.removeCallbacks(routeLandPoller)
        routeLandPoller.reset()
        // Do NOT clear lastMusicTakeoverMs (ownership cold-connect window).
        suppressRouteLandRestartUntilMs =
            System.currentTimeMillis() + PASSIVE_CONNECT_ROUTELAND_SUPPRESS_MS
        Log.d(
            "MediaController",
            "  → suppress routeLand restart for ${PASSIVE_CONNECT_ROUTELAND_SUPPRESS_MS}ms"
        )
    }

    /**
     * Restart the routeLandPoller explicitly. Used when A2DP PLAYING_STATE_CHANGED
     * → PLAYING fires, because on some devices (Xiaomi) the actual A2DP audio stream
     * starts many seconds after the device is added to the system. Pocket Casts
     * auto-pauses on the late route change, but by then the original poller started
     * from onAudioDevicesAdded has already finished.
     */
    fun restartRouteLandPoller() {
        val now = System.currentTimeMillis()
        if (now < suppressRouteLandRestartUntilMs) {
            Log.d(
                "MediaController",
                "restartRouteLandPoller skipped — passive-connect suppress " +
                    "(${suppressRouteLandRestartUntilMs - now}ms left)"
            )
            return
        }
        // Intentional music takeover still in flight (armPending not yet consumed),
        // or a late A2DP PLAYING after a real play — allow restart.
        Log.d("MediaController", "restartRouteLandPoller: resetting and starting fresh watch")
        routeLandWatchPending = false
        handler.removeCallbacks(routeLandPoller)
        routeLandPoller.reset()
        routeLandWatchPending = true
        handler.postDelayed(routeLandPoller, ROUTE_LAND_DELAYS_MS[0])
    }

    // MediaSession-based detection (covers apps hidden from AudioPlaybackCallback by audio
    // hardening, e.g. Pocket Casts with FLAG_NO_MEDIA_PROJECTION). Sessions are addressable
    // because the app already has BIND_NOTIFICATION_LISTENER_SERVICE permission.
    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var mediaListenerComponent: ComponentName? = null
    private var appPackageName: String? = null
    private val sessionCallbacks = mutableMapOf<android.media.session.MediaController, android.media.session.MediaController.Callback>()
    private val sessionLastState = mutableMapOf<android.media.session.MediaController, Int>()


    /**
     * True for real user media (music, podcasts, movies). False for battery/TTS
     * announcements (USAGE_ASSISTANT + SPEECH) and other assistant streams that
     * must not trigger takeOver("music") / auto-play after lid connect.
     */
    internal fun isUserMediaPlayback(usage: Int, contentType: Int): Boolean {
        if (isAssistantAnnouncementUsage(usage)) return false
        return usage == android.media.AudioAttributes.USAGE_MEDIA ||
            contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
            contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE ||
            // Podcasts sometimes use SPEECH with USAGE_MEDIA (already true above).
            // Bare SPEECH without MEDIA is typically TTS — ignore.
            (contentType == android.media.AudioAttributes.CONTENT_TYPE_SPEECH &&
                usage == android.media.AudioAttributes.USAGE_MEDIA)
    }

    private fun isAssistantAnnouncementUsage(usage: Int): Boolean =
        usage == android.media.AudioAttributes.USAGE_ASSISTANT ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
            usage == android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences, context: Context? = null) {
        if (this::audioManager.isInitialized) {
            if (context != null) refreshMediaSessionMonitoring(context)
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", true)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", true)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, handler)

        // Listen for audio routing changes. When the AirPods become the actual
        // media output (not just A2DP-profile-connected, but Android has fully
        // routed media to them), check if we should re-issue a play key. This
        // covers Pocket Casts (and any well-behaved app) auto-pausing on
        // audio device change during a handover.
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                addedDevices?.forEach { dev ->
                    if (dev.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return@forEach
                    val pendingMac = pendingMusicTakeoverForMac
                    Log.d("MediaController", "AudioDevice added: type=A2DP name='${dev.productName}' addr='${dev.address}' pendingFor='$pendingMac'")
                    if (pendingMac == null) return@forEach
                    // Age-out: if the pending state is older than the max age, drop it.
                    // Protects against a route blip happening minutes after a no-op takeOver.
                    val ageMs = System.currentTimeMillis() - pendingMusicTakeoverSetAt
                    if (ageMs > PENDING_TAKEOVER_MAX_AGE_MS) {
                        Log.d("MediaController", "  → pendingMac stale (${ageMs}ms old); clearing without action")
                        cancelPendingMusicTakeover()
                        return@forEach
                    }
                    // Exact MAC match — case-insensitive because some vendors uppercase
                    // and some lowercase the address string.
                    if (!dev.address.equals(pendingMac, ignoreCase = true)) {
                        Log.d("MediaController", "  → MAC mismatch (added=${dev.address} vs pending=$pendingMac); ignoring")
                        return@forEach
                    }
                    // Consume the pending state so we don't re-fire on later route changes.
                    cancelPendingMusicTakeover()
                    if (iPausedTheMedia) {
                        Log.d("MediaController", "  → route landed but iPausedTheMedia=true; user paused intentionally, NOT replaying")
                        ServiceManager.getService()?.showTakeoverIsland()
                        return@forEach
                    }
                    // Start the route-land poller. It watches the actual playback state
                    // every 200 ms for up to 2 s. If the app auto-pauses on the route
                    // change (Pocket Casts, Spotify, etc.), the poller detects
                    // isMusicActive dropping and re-issues play immediately — no
                    // fragile single-shot timing guess.
                    Log.d("MediaController", "  → expected AirPods route landed; starting routeLandPoller")
                    routeLandWatchPending = true
                    sendPlay(force = true)
                    handler.postDelayed(routeLandPoller, ROUTE_LAND_DELAYS_MS[0])
                    // Audio route is actually live now — show the takeover island.
                    // (Previously fired immediately on takeOver() request, ~3 s
                    // before reality. This matches what the user sees/hears.)
                    ServiceManager.getService()?.showTakeoverIsland()
                }
            }
        }, handler)

        // Stamp the A2DP flux timestamp on AUDIO_BECOMING_NOISY too — not just
        // on profile state changes. NOISY fires ~300-500 ms EARLIER than the
        // A2DP profile transitions when a peer device takes the AirPods, so
        // it's the earliest local signal that "the AirPods are leaving us."
        // Without this, the takeOver-suppression gate misses the window:
        // onPlaybackConfigChanged fires (because music is still active) right
        // between NOISY and the profile state change, sees no recent A2DP
        // flux, and triggers a fight-back takeOver.
        if (context != null) {
            try {
                val noisyReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: android.content.Intent?) {
                        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                            io.automated.ventures.everypods.services.AirPodsService.lastA2dpStateChangeMs = System.currentTimeMillis()
                            Log.d("MediaController", "AUDIO_BECOMING_NOISY received; stamping A2DP flux for suppression gate")
                        }
                    }
                }
                context.registerReceiver(
                    noisyReceiver,
                    android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
                Log.d("MediaController", "Registered AUDIO_BECOMING_NOISY receiver")
            } catch (e: Exception) {
                Log.w("MediaController", "Failed to register NOISY receiver: ${e.message}")
            }
        }

        // Also subscribe to MediaSessionManager to catch apps that AudioPlaybackCallback misses.
        if (context != null) {
            val app = context.applicationContext
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("enabled_notification_listeners"), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refreshMediaSessionMonitoring(app)
                },
            )
            refreshMediaSessionMonitoring(app)
        }
    }

    fun refreshMediaSessionMonitoring(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (Looper.myLooper() != handler.looper) {
            handler.post { refreshMediaSessionMonitoring(app, force) }
            return
        }
        appPackageName = app.packageName
        val component = MediaSessionAccess.enabledListener(app)
        mutableMediaSessionAccess.value = component != null
        if (!this::audioManager.isInitialized) return
        if (!force && component == mediaListenerComponent && mediaSessionsListener != null) return
        detachMediaSessions()
        if (component == null) {
            Log.w("MediaController", "Media-session detection unavailable: notification access missing for ${app.packageName}")
            return
        }
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm == null) {
                Log.w("MediaController", "MediaSessionManager not available")
                return
            }
            mediaSessionManager = msm
            val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (controllers == null) return@OnActiveSessionsChangedListener
                Log.d("MediaController", "Active media sessions changed: ${controllers.size}")
                // Stop tracking sessions that are gone.
                val current = controllers.map { it.sessionToken }.toSet()
                val gone = sessionCallbacks.keys.filter { it.sessionToken !in current }
                for (g in gone) {
                    runCatching { g.unregisterCallback(sessionCallbacks[g]!!) }
                    sessionCallbacks.remove(g)
                    sessionLastState.remove(g)
                }
                // Track new sessions.
                for (ctrl in controllers) {
                    if (sessionCallbacks.keys.any { it.sessionToken == ctrl.sessionToken }) continue
                    trackSession(ctrl)
                }
            }

            // Prime with currently active sessions, then subscribe to changes.
            msm.addOnActiveSessionsChangedListener(onSessionsChanged, component, handler)
            mediaSessionsListener = onSessionsChanged
            mediaListenerComponent = component
            val initial = msm.getActiveSessions(component)
            Log.d("MediaController", "Initial active media sessions: ${initial.size}")
            for (ctrl in initial) trackSession(ctrl)
            Log.i("MediaController", "Media-session monitoring ready: ${component.flattenToShortString()}")
        } catch (t: Exception) {
            detachMediaSessions()
            Log.w("MediaController", "MediaSession setup failed: ${t.message}")
        }
    }

    private fun detachMediaSessions() {
        mediaSessionsListener?.let { listener -> runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(listener) } }
        mediaSessionsListener = null
        mediaListenerComponent = null
        sessionCallbacks.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        sessionCallbacks.clear()
        sessionLastState.clear()
    }

    private fun trackSession(ctrl: android.media.session.MediaController) {
        val pkg = ctrl.packageName
        val startState = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
        sessionLastState[ctrl] = startState
        if (startState == PlaybackState.STATE_PLAYING) lastPlayingSessionPackage = pkg
        Log.d("MediaController", "Tracking media session: $pkg initialState=$startState")

        val cb = object : android.media.session.MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val newState = state?.state ?: PlaybackState.STATE_NONE
                val prev = sessionLastState[ctrl] ?: PlaybackState.STATE_NONE
                sessionLastState[ctrl] = newState
                Log.d("MediaController", "[session $pkg] state $prev → $newState")
                if (newState == PlaybackState.STATE_PLAYING && prev != PlaybackState.STATE_PLAYING) {
                    lastPlayingSessionPackage = pkg
                    if (relayAudioPlaybackActive) {
                        Log.d("MediaController", "Ignoring media-session start during Wi-Fi relay playback")
                        return
                    }
                    onSessionStartedPlaying(ctrl)
                }
            }

            override fun onSessionDestroyed() {
                Log.d("MediaController", "[session $pkg] destroyed")
                sessionCallbacks.remove(ctrl)
                sessionLastState.remove(ctrl)
            }
        }
        ctrl.registerCallback(cb, handler)
        sessionCallbacks[ctrl] = cb

        // Record the initial state without manufacturing a Play event when access
        // is granted or a listener reconnects. Fresh transitions remain actionable.
    }

    private fun isPlayingLocalMedia(ctrl: android.media.session.MediaController): Boolean {
        if (ctrl.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        val info: android.media.session.MediaController.PlaybackInfo? = ctrl.playbackInfo
        if (info?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) return false
        val attrs = info?.audioAttributes ?: return true
        return isUserMediaPlayback(attrs.usage, attrs.contentType)
    }

    private fun onSessionStartedPlaying(ctrl: android.media.session.MediaController) {
        if (!isPlayingLocalMedia(ctrl)) {
            Log.d("MediaController", "Ignoring session start from ${ctrl.packageName}: remote or non-media playback")
            return
        }
        if (!audioManager.isMusicActive) {
            scheduleMediaVerification(sawMedia = true, session = ctrl)
            return
        }
        // A real PAUSED -> PLAYING transition is stronger than the aggregate
        // activity baseline, including a quick manual resume after we paused media.
        requestMediaTakeover("media session ${ctrl.packageName}")
        startDetector.rearm(true)
        lastKnownIsMusicActive = true
    }

    private fun shouldSuppressAutomaticTakeoverForRelay(): Boolean =
        io.automated.ventures.everypods.relay.DeviceConnectionStore.emitting

    private fun takeoverBlocked(): Boolean = relayAudioPlaybackActive ||
        shouldSuppressAutomaticTakeoverForRelay() || AnnouncementCoordinator.isAudiblySpeaking()

    private fun requestMediaTakeover(reason: String) {
        val now = SystemClock.uptimeMillis()
        val rejection = when {
            takeoverBlocked() -> "relay or announcement active"
            now - lastSelfActionAt < SELF_ACTION_IGNORE_MS -> "recent EveryPods playback action"
            !audioManager.isMusicActive -> "no local music active"
            lastMediaTakeoverRequestAt?.let { now - it < 750L } == true -> "duplicate start event"
            else -> null
        }
        if (rejection != null) {
            Log.i("MediaController", "Media handover skipped: $rejection; trigger=$reason emitting=${shouldSuppressAutomaticTakeoverForRelay()} receiverPlayback=$relayAudioPlaybackActive")
            return
        }
        val service = ServiceManager.getService()
        if (service == null) {
            Log.w("MediaController", "Media handover skipped: AirPods service unavailable; trigger=$reason")
            return
        }
        val changedAt = io.automated.ventures.everypods.services.AirPodsService.lastA2dpStateChangeMs
        if (changedAt > 0L && System.currentTimeMillis() - changedAt <
            io.automated.ventures.everypods.services.AirPodsService.A2DP_STATE_FLUX_WINDOW_MS) {
            Log.i("MediaController", "Media handover skipped: Bluetooth route changing; trigger=$reason")
            return
        }
        cancelMediaVerification()
        lastMediaTakeoverRequestAt = now
        // The self-action window has elapsed and local media has actually started.
        // A pause remembered from yielding to a peer must not veto the user's Play.
        iPausedTheMedia = false
        Log.i("MediaController", "Media start verified; requesting takeOver(music); trigger=$reason")
        service.takeOver("music")
    }

    private fun cancelMediaVerification() {
        mediaDetectionGeneration++
        mediaVerification?.let(handler::removeCallbacks)
        mediaVerification = null
    }

    /** Bounded follow-up for empty/redacted configs and playback that becomes active later. */
    private fun scheduleMediaVerification(sawMedia: Boolean, session: android.media.session.MediaController? = null) {
        if (takeoverBlocked()) return
        if (mediaVerification != null && session == null) return
        cancelMediaVerification()
        val generation = mediaDetectionGeneration
        val delays = longArrayOf(120L, 250L, 500L)
        var attempt = 0
        val verify = object : Runnable {
            override fun run() {
                if (generation != mediaDetectionGeneration) return
                if (takeoverBlocked() || (session != null && !isPlayingLocalMedia(session))) {
                    cancelMediaVerification()
                    return
                }
                val active = audioManager.isMusicActive
                val configs = audioManager.activePlaybackConfigurations
                val hasMedia = configs.any { isUserMediaPlayback(it.audioAttributes.usage, it.audioAttributes.contentType) }
                val sessionMedia = sessionCallbacks.keys.any(::isPlayingLocalMedia)
                val evidence = when {
                    hasMedia || sessionMedia || (sawMedia && configs.isEmpty()) -> MediaPlaybackEvidence.USER_MEDIA
                    configs.isEmpty() -> MediaPlaybackEvidence.UNKNOWN
                    else -> MediaPlaybackEvidence.OTHER
                }
                val decision = startDetector.observe(active, evidence, blocked = false)
                lastKnownIsMusicActive = startDetector.active
                if (decision == MediaStartDecision.REQUEST_HANDOVER) {
                    requestMediaTakeover("playback verification")
                    cancelMediaVerification()
                    return
                }
                if (active && evidence != MediaPlaybackEvidence.UNKNOWN) {
                    cancelMediaVerification()
                    return
                }
                attempt++
                if (attempt < delays.size) handler.postDelayed(this, delays[attempt])
                else {
                    Log.i("MediaController", "Media verification ended: localActive=$active mediaEvidence=$evidence sessionAccess=${mutableMediaSessionAccess.value}")
                    cancelMediaVerification()
                }
            }
        }
        mediaVerification = verify
        handler.postDelayed(verify, delays[0])
    }

    private fun ignorePlayback(configs: List<AudioPlaybackConfiguration>?): Boolean {
        if (relayAudioPlaybackActive || AnnouncementCoordinator.isAudiblySpeaking()) return true
        return !configs.isNullOrEmpty() && configs.all {
            AnnouncementAudioAttributes.isAnnouncementSpeech(it.audioAttributes.usage, it.audioAttributes.contentType)
        }
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            if (ignorePlayback(configs)) {
                cancelMediaVerification()
                handler.removeCallbacks(processLatestPlayback)
                playbackCallbackPending = false
                return
            }
            latestPlaybackConfigs = configs?.toList()
            // Keep the newest snapshot, with a bounded delay from the FIRST event.
            // Stop/start bursts cannot discard the actual start or starve the drain.
            if (!playbackCallbackPending) {
                playbackCallbackPending = true
                handler.postDelayed(processLatestPlayback, PLAYBACK_COALESCE_MS)
            }
        }
    }

    private fun processPlaybackConfigs(configs: List<AudioPlaybackConfiguration>?) {
        if (ignorePlayback(configs)) return
        val now = SystemClock.uptimeMillis()
        val active = audioManager.isMusicActive
        val evidence = when {
            configs?.any { isUserMediaPlayback(it.audioAttributes.usage, it.audioAttributes.contentType) } == true -> MediaPlaybackEvidence.USER_MEDIA
            configs.isNullOrEmpty() && sessionCallbacks.keys.any(::isPlayingLocalMedia) -> MediaPlaybackEvidence.USER_MEDIA
            configs.isNullOrEmpty() -> MediaPlaybackEvidence.UNKNOWN
            else -> MediaPlaybackEvidence.OTHER
        }
        Log.d("MediaController", "Playback observed: active=$active configurations=${configs?.size ?: 0} evidence=$evidence previousActive=${startDetector.active}")
        if (!active && lastPlayWithReplay && now - lastPlayTime < 2500L) {
            lastPlayWithReplay = false
            sendPlay()
            startDetector.rearm(true)
            lastKnownIsMusicActive = true
            return
        }
        val selfAction = now - lastSelfActionAt < SELF_ACTION_IGNORE_MS
        val decision = startDetector.observe(active, evidence, blocked = takeoverBlocked() || selfAction)
        lastKnownIsMusicActive = startDetector.active
        when (decision) {
            MediaStartDecision.REQUEST_HANDOVER -> requestMediaTakeover("playback callback")
            MediaStartDecision.VERIFY -> scheduleMediaVerification(evidence == MediaPlaybackEvidence.USER_MEDIA)
            MediaStartDecision.IGNORE -> if (!active) cancelMediaVerification()
        }
        if (active && evidence == MediaPlaybackEvidence.USER_MEDIA && shouldSuppressAutomaticTakeoverForRelay()) {
            Log.i("MediaController", "Media handover paused: this device is sending Wi-Fi relay audio")
        }
        if (configs != null && !iPausedTheMedia) {
            val localMac = ServiceManager.getService()?.localMac
            if (!localMac.isNullOrEmpty()) ServiceManager.getService()?.aacpManager?.sendMediaInformataion(localMac, active)
            handler.postDelayed({ userPlayedTheMedia = audioManager.isMusicActive }, 7)
        }
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    @Synchronized
    fun sendPlayPause() {
        val wasActive = audioManager.isMusicActive
        Log.d("MediaController", "Sending play/pause toggle (wasActive=$wasActive)")
        if (!wasActive && tryDirectSessionResume("play/pause")) {
            lastSelfActionAt = SystemClock.uptimeMillis()
            iPausedTheMedia = false
            return
        }
        dispatchPlayPauseKey()
        lastSelfActionAt = SystemClock.uptimeMillis()
        // If music was active, this press paused it → remember so auto-resume on ear-in works.
        // If music was not active, this press is a play → clear the pause flag.
        iPausedTheMedia = wasActive
        if (wasActive) userPlayedTheMedia = false
    }

    /** Package of the session most recently seen PLAYING (preferred resume target). */
    @Volatile
    private var lastPlayingSessionPackage: String? = null

    private fun dispatchPlayPauseKey() {
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
    }

    /**
     * Android may have no media-button session ("Media button session is null"), so a
     * generic key goes nowhere. Resume the best paused session directly instead.
     * Returns true when a direct play() was issued (a verification fallback is scheduled).
     */
    private fun tryDirectSessionResume(reason: String): Boolean {
        val msm = mediaSessionManager
        val component = mediaListenerComponent
        val controllers = if (msm != null && component != null) {
            runCatching { msm.getActiveSessions(component) }.getOrElse {
                Log.i("MediaController", "Stem resume: getActiveSessions failed (${it.message}); using media key")
                emptyList()
            }
        } else emptyList()
        val own = setOfNotNull(appPackageName)
        val candidates = controllers.map { c ->
            val st = c.playbackState
            StemResumeSessionPicker.Candidate(
                packageName = c.packageName,
                state = st?.state ?: PlaybackState.STATE_NONE,
                lastUpdateTime = st?.lastPositionUpdateTime ?: 0L,
                usage = c.playbackInfo?.audioAttributes?.usage,
                isRemote = c.playbackInfo?.playbackType ==
                    android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
                hasPlayAction = st == null || st.actions == 0L ||
                    (st.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L,
            )
        }
        val anyPlaying = candidates.any {
            it.packageName !in own && StemResumeSessionPicker.isResumed(it.state)
        }
        val target = StemResumeSessionPicker.pick(candidates, own, lastPlayingSessionPackage)
        val path = StemResumeSessionPicker.decide(
            musicActive = false,
            anySessionPlaying = anyPlaying,
            hasSessionAccess = component != null,
            target = target,
        )
        if (path != StemResumeSessionPicker.Path.DIRECT_SESSION || target == null) {
            Log.i("MediaController", "Stem resume ($reason): path=KEY_EVENT access=${component != null} sessions=${candidates.size} anyPlaying=$anyPlaying")
            return false
        }
        val ctrl = controllers.firstOrNull { it.packageName == target.packageName }
        if (ctrl == null) return false
        return try {
            ctrl.transportControls.play()
            Log.i("MediaController", "Stem resume ($reason): path=DIRECT_SESSION pkg=${target.packageName} state=${target.state}")
            handler.postDelayed({
                val now = runCatching { ctrl.playbackState?.state }.getOrNull()
                if (StemResumeSessionPicker.isResumed(now) || audioManager.isMusicActive) {
                    Log.i("MediaController", "Stem resume: DIRECT_SESSION confirmed pkg=${target.packageName} state=$now")
                } else {
                    Log.i("MediaController", "Stem resume: DIRECT_SESSION not playing after 1s (pkg=${target.packageName} state=$now); path=KEY_EVENT_RETRY")
                    dispatchPlayPauseKey()
                    lastSelfActionAt = SystemClock.uptimeMillis()
                }
            }, 1000L)
            true
        } catch (t: Exception) {
            Log.i("MediaController", "Stem resume: direct play failed for ${target.packageName} (${t.message}); path=KEY_EVENT")
            false
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        Log.d("MediaController", "sendPause called: iPausedTheMedia=$iPausedTheMedia, userPlayedTheMedia=$userPlayedTheMedia, isMusicActive=${audioManager.isMusicActive}, force=$force")
        // Cancel any pending music-takeover replay — if we're pausing, the user
        // doesn't want auto-resume when a future A2DP route lands.
        cancelPendingMusicTakeover()
        routeLandWatchPending = false
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            Log.d("MediaController", "  → DISPATCHING PAUSE KEY EVENT")
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        } else {
            Log.d("MediaController", "  → skipped (not music active or user is controlling playback)")
        }
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationalAwarenessVolume: $conversationalAwarenessVolume, relativeVolume: $relativeVolume")

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                sendPause(force = true)
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                sendPlay()
            }
            initialVolume = null
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
