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


package io.automated.ventures.everypods.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log

/**
 * Audio attributes for spoken announcements (system TTS and ElevenLabs).
 *
 * Announcements normally use USAGE_ASSISTANT so they stay out of the
 * music/podcast detector. Since Android 16/17 (flag
 * `streamAssistantNotAliasedToMusic`) USAGE_ASSISTANT plays on its own
 * STREAM_ASSISTANT volume, which is no longer tied to media volume and can be
 * left muted / at 0 with no obvious UI to fix it. When that happens every
 * announcement and the voice preview are synthesized and "played" at volume 0
 * (completely silent). In that case fall back to USAGE_ASSISTANCE_ACCESSIBILITY,
 * which is aliased to media volume, routes to A2DP like media, and is still
 * excluded from user-media detection.
 */
object AnnouncementAudioAttributes {
    private const val TAG = "AnnouncementAudio"

    /** AudioManager.STREAM_ASSISTANT is @hide; AudioSystem.STREAM_ASSISTANT == 11. */
    internal const val STREAM_ASSISTANT = 11

    @Volatile private var lastLoggedUsage: Int = -1

    /** Pure selection logic, unit-tested. */
    internal fun chooseUsage(assistantStreamMuted: Boolean, assistantStreamVolume: Int): Int =
        if (assistantStreamMuted || assistantStreamVolume <= 0) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_ASSISTANT
        }

    fun usage(context: Context): Int {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioAttributes.USAGE_ASSISTANT
        val state = runCatching {
            am.isStreamMute(STREAM_ASSISTANT) to am.getStreamVolume(STREAM_ASSISTANT)
        }.getOrNull() ?: return AudioAttributes.USAGE_ASSISTANT
        val usage = chooseUsage(state.first, state.second)
        if (usage != lastLoggedUsage) {
            lastLoggedUsage = usage
            if (usage == AudioAttributes.USAGE_ASSISTANT) {
                Log.d(TAG, "Announcements use USAGE_ASSISTANT (assistant stream volume=${state.second})")
            } else {
                Log.w(
                    TAG,
                    "Assistant stream is silent (muted=${state.first}, volume=${state.second}); " +
                        "announcements fall back to USAGE_ASSISTANCE_ACCESSIBILITY (media volume)"
                )
            }
        }
        return usage
    }

    fun speech(context: Context): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage(context))
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** True for playback configs that look like our own spoken announcements. */
    fun isAnnouncementSpeech(usage: Int, contentType: Int): Boolean =
        contentType == AudioAttributes.CONTENT_TYPE_SPEECH &&
            (usage == AudioAttributes.USAGE_ASSISTANT ||
                usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
}
