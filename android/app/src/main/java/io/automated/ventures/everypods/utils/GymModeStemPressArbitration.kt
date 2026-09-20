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
     * Resolve long-press for **one bud** while Gym Mode may be on.
     *
     * - AirPods Controls `TOGGLE_GYM_MODE` on **this** bud wins even when gym is on
     *   (so that bud can enter and exit Gym Mode).
     * - The other bud is unaffected: it uses its own Controls / Gym Press Actions map.
     * - Gym Press Actions must never own gym on/off — if the gym overlay map still
     *   has `TOGGLE_GYM_MODE`, fall back to timer reset.
     */
    fun resolveLongPressAction(
        gymModeEnabled: Boolean,
        normalLongPress: StemAction,
        gymLongPress: StemAction,
    ): StemAction {
        if (!gymModeEnabled) return normalLongPress
        if (normalLongPress == StemAction.TOGGLE_GYM_MODE) return StemAction.TOGGLE_GYM_MODE
        if (gymLongPress == StemAction.TOGGLE_GYM_MODE) return StemAction.GYM_TIMER_RESET
        return gymLongPress
    }

    /** True when Gym Press Actions long-press for this bud is owned by AirPods Controls. */
    fun isGymLongPressLockedByControls(normalLongPress: StemAction): Boolean =
        normalLongPress == StemAction.TOGGLE_GYM_MODE
}
