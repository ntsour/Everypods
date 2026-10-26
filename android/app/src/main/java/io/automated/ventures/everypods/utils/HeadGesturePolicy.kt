package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.*

/** Pure policy shared by packet dispatch and tracking ownership reconciliation. */
object HeadGesturePolicy {
    fun context(ringing: Boolean, activeCall: Boolean, testing: Boolean, gym: Boolean) = when {
        ringing -> HeadGestureContext.RINGING
        activeCall -> HeadGestureContext.ACTIVE_CALL
        testing -> HeadGestureContext.TEST
        gym -> HeadGestureContext.GYM
        else -> HeadGestureContext.NORMAL
    }

    data class Owners(val detect: Boolean, val track: Boolean)
    fun owners(settings: HeadGestureSettings, context: HeadGestureContext, local: Boolean,
               knownWorn: Boolean, diagnostics: Boolean, announcementAudible: Boolean = false, knownOutOfEar: Boolean = false): Owners {
        if (knownOutOfEar) return Owners(false, false)
        val detect = local && knownWorn && HeadGesture.entries.any { settings.resolve(it, context, announcementAudible) != HeadGestureDispatch.None }
        return Owners(detect, local && (detect || diagnostics))
    }
}
