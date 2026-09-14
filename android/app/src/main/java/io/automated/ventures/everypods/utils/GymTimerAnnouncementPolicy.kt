package io.automated.ventures.everypods.utils

/** Applies the optional cue settings while preserving start, control, and completion speech. */
object GymTimerAnnouncementPolicy {
    fun eventForSpeech(
        event: GymTimer.AnnouncementEvent,
        intermediateAnnouncementsEnabled: Boolean,
        finalCountdownEnabled: Boolean
    ): GymTimer.AnnouncementEvent? = when (event) {
        is GymTimer.AnnouncementEvent.CountdownCue -> event.copy(
            milestone = event.milestone.takeIf { intermediateAnnouncementsEnabled },
            finalSecond = event.finalSecond?.takeIf { finalCountdownEnabled }
        ).takeIf { it.milestone != null || it.finalSecond != null }

        is GymTimer.AnnouncementEvent.StopwatchInterval,
        is GymTimer.AnnouncementEvent.HiitPhaseStarted,
        is GymTimer.AnnouncementEvent.LapRecorded -> event.takeIf { intermediateAnnouncementsEnabled }

        is GymTimer.AnnouncementEvent.HiitCountdown -> event.takeIf { finalCountdownEnabled }

        else -> event
    }
}
