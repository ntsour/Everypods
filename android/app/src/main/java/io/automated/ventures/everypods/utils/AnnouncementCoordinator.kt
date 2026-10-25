package io.automated.ventures.everypods.utils

import android.content.Context
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one place EveryPods schedules spoken feedback.  It deliberately keeps at
 * most one utterance active and one pending request, so stale timer numbers can
 * never build up behind a system announcement.
 */
object AnnouncementCoordinator {
    private const val TAG = "AnnouncementCoordinator"
    private val speechStartListeners = CopyOnWriteArraySet<() -> Unit>()

    enum class Priority(val rank: Int) {
        TIMER_PROGRESS(0),
        TIMER_TIMING(1),
        TIMER_SEQUENCE(2),
        SYSTEM(2),
        CONTROL(3),
        SAFETY(4),
    }

    private data class Request(
        val token: Long,
        val text: String,
        val priority: Priority,
        val languageTag: String? = null,
    )

    private var nextToken = 0L
    private var active: Request? = null
    private var pending: Request? = null
    private val sequencePending = ArrayDeque<Request>()

    fun announce(context: Context, text: String, priority: Priority) {
        announceSpeech(context, LocalizedSpeech(text), priority)
    }

    fun announceSpeech(context: Context, speech: LocalizedSpeech, priority: Priority) {
        val request = synchronized(this) {
            Request(++nextToken, speech.text, priority, speech.languageTag).also { incoming ->
                val current = active
                when {
                    current == null -> {
                        Log.d(TAG, "Starting ${incoming.priority} announcement: ${incoming.text.take(48)}")
                        active = incoming
                    }
                    incoming.priority == Priority.TIMER_SEQUENCE &&
                        current.priority == Priority.TIMER_SEQUENCE -> {
                        // Preparation numbers belong together: retain their order.
                        Log.d(TAG, "Queueing preparation announcement: ${incoming.text}")
                        sequencePending.addLast(incoming)
                        return
                    }
                    incoming.priority.rank > current.priority.rank -> {
                        // A user control or safety message must not wait behind stale speech.
                        Log.d(TAG, "Replacing ${current.priority} with ${incoming.priority}: ${incoming.text.take(48)}")
                        active = incoming
                        pending = null
                        sequencePending.clear()
                        stopEngines()
                    }
                    incoming.priority == Priority.TIMER_PROGRESS ||
                        incoming.priority == Priority.TIMER_TIMING -> {
                        // Timing cues are useful only at their intended instant. Do not queue them.
                        Log.d(TAG, "Dropping stale ${incoming.priority} announcement: ${incoming.text}")
                        return
                    }
                    pending == null || incoming.priority.rank >= pending!!.priority.rank -> {
                        Log.d(TAG, "Queueing ${incoming.priority} announcement behind ${current.priority}")
                        pending = incoming
                        return
                    }
                    else -> return
                }
            }
        }
        speak(context.applicationContext, request)
    }

    fun stop() {
        synchronized(this) {
            active = null
            pending = null
            sequencePending.clear()
            stopEngines()
        }
    }

    private fun speak(context: Context, request: Request) {
        val onDone = { complete(context, request.token) }
        val languageForSystemTts = request.languageTag
            ?: AnnouncementPrefs.languageForText(context, request.text)
        if (AnnouncementPrefs.ttsEngine(context) == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS) {
            val apiKey = AnnouncementPrefs.elevenLabsApiKey(context)
            if (apiKey.isNotBlank()) {
                ElevenLabsEngine.speak(
                    context = context,
                    text = request.text,
                    apiKey = apiKey,
                    voiceId = AnnouncementPrefs.elevenLabsVoiceId(context),
                    languageCode = request.languageTag
                        ?: AnnouncementPrefs.elevenLabsLanguageCode(context),
                    onDone = onDone,
                    onFallback = { reason ->
                        Log.w(TAG, "ElevenLabs failed ($reason), using system TTS")
                        TtsEngine.speak(context, request.text, languageForSystemTts, onDone)
                    },
                )
                return
            }
        }
        TtsEngine.speak(context, request.text, languageForSystemTts, onDone)
    }

    private fun complete(context: Context, token: Long) {
        val next = synchronized(this) {
            if (active?.token != token) return
            // A normal notification may arrive during the short preparation
            // sequence. Keep it until every number has been spoken.
            active = if (sequencePending.isEmpty()) {
                pending.also { pending = null }
            } else {
                sequencePending.removeFirst()
            }
            active
        }
        if (next == null) {
            Log.d(TAG, "Announcement completed; queue is empty")
            return
        }
        Log.d(TAG, "Continuing with ${next.priority} announcement: ${next.text.take(48)}")
        speak(context, next)
    }

    private fun stopEngines() {
        TtsEngine.stop()
        ElevenLabsEngine.stop()
    }

    /** True only while speech is reaching the audio device, never during queues or grace periods. */
    fun isAudiblySpeaking(): Boolean =
        TtsEngine.isAudiblySpeaking() || ElevenLabsEngine.isAudiblySpeaking()

    /** Prevent media-route recovery from treating EveryPods' own speech as new music. */
    fun onSpeechAudibleStarted() {
        MediaController.cancelRouteRecoveryForAnnouncement()
        speechStartListeners.forEach { listener ->
            runCatching(listener).onFailure { Log.w(TAG, "Speech-start listener failed", it) }
        }
    }

    fun addSpeechStartListener(listener: () -> Unit) {
        speechStartListeners.add(listener)
    }

    fun removeSpeechStartListener(listener: () -> Unit) {
        speechStartListeners.remove(listener)
    }

    /** True while an AnnouncementCoordinator request is currently audible. */
    fun isManagedAnnouncementAudible(): Boolean =
        synchronized(this) { active != null } && isAudiblySpeaking()
}

/** Keeps the locale used to format generated speech attached to queued utterances. */
data class LocalizedSpeech(val text: String, val languageTag: String? = null)
