/*
    ProPods - AirPods liberated from Apple’s ecosystem
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

@file:OptIn(ExperimentalEncodingApi::class)

package io.nikos.propods.utils

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import io.nikos.propods.services.NotificationAnnouncementService
import io.nikos.propods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null

    private const val PAUSED_FOR_OTHER_DEVICE_CLEAR_MS = 500L
    private val clearPausedForOtherDeviceRunnable = Runnable {
        pausedForOtherDevice = false
        Log.d("MediaController", "Cleared pausedForOtherDevice after timeout, resuming normal playback monitoring")
    }

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    // MediaSession-based detection (covers apps hidden from AudioPlaybackCallback by audio
    // hardening, e.g. Pocket Casts with FLAG_NO_MEDIA_PROJECTION). Sessions are addressable
    // because the app already has BIND_NOTIFICATION_LISTENER_SERVICE permission.
    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionCallbacks = mutableMapOf<android.media.session.MediaController, android.media.session.MediaController.Callback>()
    private val sessionLastState = mutableMapOf<android.media.session.MediaController, Int>()

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences, context: Context? = null) {
        if (this::audioManager.isInitialized) {
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

        audioManager.registerAudioPlaybackCallback(cb, null)

        // Also subscribe to MediaSessionManager to catch apps that AudioPlaybackCallback misses.
        if (context != null) initMediaSessions(context)
    }

    private fun initMediaSessions(context: Context) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (msm == null) {
                Log.w("MediaController", "MediaSessionManager not available")
                return
            }
            mediaSessionManager = msm
            val listenerComponent = ComponentName(context, NotificationAnnouncementService::class.java)

            val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (controllers == null) return@OnActiveSessionsChangedListener
                Log.d("MediaController", "Active media sessions changed: ${controllers.size}")
                // Stop tracking sessions that are gone.
                val current = controllers.toSet()
                val gone = sessionCallbacks.keys - current
                for (g in gone) {
                    runCatching { g.unregisterCallback(sessionCallbacks[g]!!) }
                    sessionCallbacks.remove(g)
                    sessionLastState.remove(g)
                }
                // Track new sessions.
                for (ctrl in controllers) {
                    if (sessionCallbacks.containsKey(ctrl)) continue
                    trackSession(ctrl)
                }
            }

            // Prime with currently active sessions, then subscribe to changes.
            val initial = runCatching { msm.getActiveSessions(listenerComponent) }.getOrNull().orEmpty()
            Log.d("MediaController", "Initial active media sessions: ${initial.size}")
            for (ctrl in initial) trackSession(ctrl)
            msm.addOnActiveSessionsChangedListener(onSessionsChanged, listenerComponent, handler)
        } catch (t: Throwable) {
            Log.w("MediaController", "MediaSession setup failed: ${t.message}")
        }
    }

    private fun trackSession(ctrl: android.media.session.MediaController) {
        val pkg = ctrl.packageName
        val startState = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
        sessionLastState[ctrl] = startState
        Log.d("MediaController", "Tracking media session: $pkg initialState=$startState")

        val cb = object : android.media.session.MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val newState = state?.state ?: PlaybackState.STATE_NONE
                val prev = sessionLastState[ctrl] ?: PlaybackState.STATE_NONE
                sessionLastState[ctrl] = newState
                Log.d("MediaController", "[session $pkg] state $prev → $newState")
                if (newState == PlaybackState.STATE_PLAYING && prev != PlaybackState.STATE_PLAYING) {
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

        // If session is already PLAYING when we attach (e.g. app launched before us),
        // honor it once. Otherwise we'd miss apps that started before MediaController init.
        if (startState == PlaybackState.STATE_PLAYING) {
            handler.post { onSessionStartedPlaying(ctrl) }
        }
    }

    private fun onSessionStartedPlaying(ctrl: android.media.session.MediaController) {
        val pkg = ctrl.packageName ?: "(?)"
        val usage = ctrl.playbackInfo?.audioAttributes?.usage
        Log.d("MediaController", "[session $pkg] STATE_PLAYING usage=$usage")

        // Same gates the AudioPlaybackCallback path uses.
        if (usage != null && usage != AudioAttributes.USAGE_MEDIA) {
            Log.d("MediaController", "  ignoring: usage is not USAGE_MEDIA")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
            Log.d("MediaController", "  ignoring: within self-action window")
            return
        }
        if (recentlyLostOwnership) {
            Log.d("MediaController", "  ignoring: recentlyLostOwnership=true (anti-pingpong)")
            return
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "  ignoring: pausedWhileTakingOver=true")
            return
        }
        if (iPausedTheMedia) {
            Log.d("MediaController", "  ignoring: iPausedTheMedia=true (we paused this ourselves)")
            return
        }
        Log.d("MediaController", "  → requesting takeOver(\"music\") from MediaSession event")
        ServiceManager.getService()?.takeOver("music")
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val isActive = audioManager.isMusicActive
            Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive")

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = true
                return
            }

            if (now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                // Don't reset the timer on debounce — otherwise rapid cascading callbacks
                // (e.g. HyperOS firing 3+ events within 50ms when YouTube starts) keep
                // resetting the window and the meaningful event never gets processed.
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = isActive
                return
            }

            Log.d("MediaController", "Configs received: ${configs?.size ?: 0} configurations")
            // Inspect both usage and contentType. Many apps (Pocket Casts, etc.) leave
            // contentType=UNKNOWN but always set usage=USAGE_MEDIA for media playback.
            data class AttrPair(val usage: Int, val contentType: Int)
            val activeAttrs = configs?.mapNotNull { config ->
                Log.d("MediaController", "Processing config: ${config}, audioAttributes: ${config.audioAttributes}")
                config.audioAttributes?.let { attrs ->
                    Log.d("MediaController", "Config usage=${attrs.usage} contentType=${attrs.contentType}")
                    AttrPair(attrs.usage, attrs.contentType)
                }
            }?.toSet() ?: emptySet()

            Log.d("MediaController", "Active audio attrs: $activeAttrs")

            val hasNewMusicOrMovie = activeAttrs.any { a ->
                // Primary signal: usage=USAGE_MEDIA covers music, podcasts, movies, audiobooks.
                a.usage == android.media.AudioAttributes.USAGE_MEDIA ||
                // Fallback: explicit content type for older/odd apps.
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE ||
                a.contentType == android.media.AudioAttributes.CONTENT_TYPE_SPEECH
            }

            Log.d("MediaController", "Has new music or movie: $hasNewMusicOrMovie")

            if (pausedForOtherDevice) {
                handler.removeCallbacks(clearPausedForOtherDeviceRunnable)
                handler.postDelayed(clearPausedForOtherDeviceRunnable, PAUSED_FOR_OTHER_DEVICE_CLEAR_MS)

                if (isActive) {
                    Log.d("MediaController", "Detected play while pausedForOtherDevice; attempting to take over")
                    if (!recentlyLostOwnership && hasNewMusicOrMovie) {
                        pausedForOtherDevice = false
                        userPlayedTheMedia = true
                        if (!pausedWhileTakingOver) {
                            ServiceManager.getService()?.takeOver("music")
                        }
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss or no new music/movie")
                    }
                } else {
                    Log.d("MediaController", "Still not active while pausedForOtherDevice; will clear state after timeout")
                }

                lastKnownIsMusicActive = isActive
                return
            }

            if (configs != null && !iPausedTheMedia) {
                val localMac = ServiceManager.getService()?.localMac ?: return
                if (localMac == "") return
                ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
                    localMac,
                    isActive
                )
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                handler.postDelayed({
                    userPlayedTheMedia = audioManager.isMusicActive
                    if (audioManager.isMusicActive) {
                        pausedForOtherDevice = false
                    }
                }, 7)
            }

            Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
            if (!pausedWhileTakingOver && isActive && hasNewMusicOrMovie) {
                if (lastKnownIsMusicActive != true) {
                    if (!recentlyLostOwnership) {
                        Log.d("MediaController", "Music/movie is active and not pausedWhileTakingOver; requesting takeOver")
                        ServiceManager.getService()?.takeOver("music")
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss")
                    }
                }
            } else if (!pausedWhileTakingOver && !isActive && hasNewMusicOrMovie && lastKnownIsMusicActive != true && !recentlyLostOwnership) {
                // HyperOS quirk: AudioPlaybackCallback fires with media configs before
                // audioManager.isMusicActive flips to true. Re-check after 500ms.
                Log.d("MediaController", "Media config seen but isMusicActive=false; scheduling delayed re-check")
                handler.postDelayed({
                    if (audioManager.isMusicActive && !pausedWhileTakingOver && !recentlyLostOwnership && lastKnownIsMusicActive != true) {
                        Log.d("MediaController", "Delayed re-check: music now active, requesting takeOver")
                        ServiceManager.getService()?.takeOver("music")
                        lastKnownIsMusicActive = true
                    } else {
                        Log.d("MediaController", "Delayed re-check: still not music-active or gated; isMusicActive=${audioManager.isMusicActive}")
                    }
                }, 500)
            }

            lastKnownIsMusicActive = hasNewMusicOrMovie && isActive
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
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
        // If music was active, this press paused it → remember so auto-resume on ear-in works.
        // If music was not active, this press is a play → clear the pause flag.
        iPausedTheMedia = wasActive
        if (wasActive) userPlayedTheMedia = false
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
        Log.d("MediaController", "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, isMusicActive: ${audioManager.isMusicActive}, force: $force")
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
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
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
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
