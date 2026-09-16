package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressBudType
import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType
import io.automated.ventures.everypods.data.StemAction

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

    /**
     * When Gym Mode is on, stem long-press normally uses the gym overlay action
     * (default timer reset). If AirPods Controls assigned TOGGLE_GYM_MODE to the
     * non-gym long-press, keep that so the same gesture can enter and exit Gym Mode.
     */
    fun resolveLongPressAction(
        gymModeEnabled: Boolean,
        normalLongPress: StemAction,
        gymLongPress: StemAction,
    ): StemAction {
        if (!gymModeEnabled) return normalLongPress
        if (normalLongPress == StemAction.TOGGLE_GYM_MODE) return normalLongPress
        return gymLongPress
    }
}
