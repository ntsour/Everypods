package io.automated.ventures.everypods.data

enum class HeadGesture { NOD, SHAKE }
enum class HeadGestureCallAction { ANSWER, DECLINE, MUTE_TOGGLE, HANG_UP }
enum class HeadGestureContext { RINGING, ACTIVE_CALL, TEST, GYM, NORMAL }

data class GestureTuning(val amplitude: Int = 800, val repetitions: Int = 2, val cooldownMs: Int = 2000) {
    fun validated() = GestureTuning(
        ((amplitude.coerceIn(200, 2000) + 50) / 100) * 100,
        repetitions.coerceIn(1, 4),
        ((cooldownMs.coerceIn(500, 5000) + 250) / 500) * 500,
    )
}

data class HeadGestureSettings(
    val enabled: Boolean = false,
    val shakeStopsAnnouncements: Boolean = true,
    val normal: Map<HeadGesture, StemAction?> = emptyMap(),
    val gym: Map<HeadGesture, StemAction?> = emptyMap(),
    val calls: Map<HeadGestureCallAction, HeadGesture?> = emptyMap(),
    val tuning: Map<HeadGesture, GestureTuning> = emptyMap(),
    val gymTuning: Map<HeadGesture, GestureTuning> = emptyMap(),
    val separateGymTuning: Boolean = false,
) {
    fun tuningFor(gesture: HeadGesture, gymOn: Boolean) =
        (if (gymOn && separateGymTuning) gymTuning else tuning)[gesture] ?: GestureTuning()

    fun callAction(gesture: HeadGesture, ringing: Boolean): HeadGestureCallAction? =
        HeadGestureCallAction.entries.firstOrNull {
            calls[it] == gesture && (if (ringing) it in ringingActions else it !in ringingActions)
        }

    companion object {
        val ringingActions = setOf(HeadGestureCallAction.ANSWER, HeadGestureCallAction.DECLINE)
    }
}

/** Pure routing: call contexts always consume gestures, even when unassigned. */
sealed interface HeadGestureDispatch {
    data class Action(val action: StemAction) : HeadGestureDispatch
    data class Call(val action: HeadGestureCallAction) : HeadGestureDispatch
    data object StopAnnouncement : HeadGestureDispatch
    data object Test : HeadGestureDispatch
    data object None : HeadGestureDispatch
}

fun HeadGestureSettings.resolve(
    gesture: HeadGesture, context: HeadGestureContext, announcementAudible: Boolean = false,
): HeadGestureDispatch {
    if (context == HeadGestureContext.TEST) return HeadGestureDispatch.Test
    if (!enabled) return HeadGestureDispatch.None
    // Calls consume unassigned gestures too: speech must never steal a call gesture.
    if (context == HeadGestureContext.RINGING || context == HeadGestureContext.ACTIVE_CALL) {
        return callAction(gesture, context == HeadGestureContext.RINGING)?.let(HeadGestureDispatch::Call)
            ?: HeadGestureDispatch.None
    }
    if (announcementAudible && shakeStopsAnnouncements && gesture == HeadGesture.SHAKE) {
        return HeadGestureDispatch.StopAnnouncement
    }
    return (if (context == HeadGestureContext.GYM) gym else normal)[gesture]
        ?.let(HeadGestureDispatch::Action) ?: HeadGestureDispatch.None
}
