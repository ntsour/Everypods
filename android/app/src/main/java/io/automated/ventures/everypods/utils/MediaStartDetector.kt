/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils

/** Playback evidence can be temporarily unavailable even while isMusicActive is true. */
internal enum class MediaPlaybackEvidence { USER_MEDIA, UNKNOWN, OTHER }
internal enum class MediaStartDecision { IGNORE, VERIFY, REQUEST_HANDOVER }

internal class MediaStartDetector {
    var active: Boolean = false
        private set

    fun observe(musicActive: Boolean, evidence: MediaPlaybackEvidence, blocked: Boolean): MediaStartDecision {
        if (!musicActive) {
            active = false
            return if (evidence == MediaPlaybackEvidence.USER_MEDIA && !blocked) MediaStartDecision.VERIFY else MediaStartDecision.IGNORE
        }
        if (evidence == MediaPlaybackEvidence.UNKNOWN) {
            // An empty configuration list is unknown, not a stop or a fresh start.
            return if (!active && !blocked) MediaStartDecision.VERIFY else MediaStartDecision.IGNORE
        }
        if (evidence == MediaPlaybackEvidence.OTHER) return MediaStartDecision.IGNORE
        val freshStart = !active
        active = true
        return if (freshStart && !blocked) MediaStartDecision.REQUEST_HANDOVER else MediaStartDecision.IGNORE
    }

    /** Ending a relay establishes a baseline. Continuing source playback is not a new Play. */
    fun rearm(musicActive: Boolean) { active = musicActive }
}
