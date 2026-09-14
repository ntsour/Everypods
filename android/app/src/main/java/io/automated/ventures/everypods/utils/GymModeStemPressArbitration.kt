package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressBudType
import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType

/** Prevents the first click of a Gym Mode multi-press from reaching media controls. */
object GymModeStemPressArbitration {
    const val SINGLE_PRESS_DELAY_MS = 250L
    const val MULTI_PRESS_SINGLE_GUARD_MS = 250L

    fun shouldDeferSinglePress(gymModeEnabled: Boolean, type: StemPressType): Boolean =
        gymModeEnabled && type == StemPressType.SINGLE_PRESS

    fun supersedesDeferredSinglePress(
        pendingBud: StemPressBudType,
        incomingBud: StemPressBudType,
        incomingType: StemPressType
    ): Boolean = pendingBud == incomingBud && incomingType != StemPressType.SINGLE_PRESS

    fun suppressesSinglePressAfterMultiPress(
        gymModeEnabled: Boolean,
        lastMultiPressAtMs: Long?,
        nowMs: Long,
    ): Boolean = gymModeEnabled && lastMultiPressAtMs != null &&
        nowMs - lastMultiPressAtMs in 0..MULTI_PRESS_SINGLE_GUARD_MS
}
