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
     * - [gymLongPress] is `null` when the user never saved a Gym Press Actions
     *   long-press for this bud (pref key absent). In that case the bud keeps its
     *   normal Controls long-press ("Same as Controls") instead of silently becoming
     *   timer reset. An explicitly saved value (even `GYM_TIMER_RESET`) is respected.
     * - Gym Press Actions must never own gym on/off — if the gym overlay map still
     *   has `TOGGLE_GYM_MODE`, fall back to timer reset.
     */
    fun resolveLongPressAction(
        gymModeEnabled: Boolean,
        normalLongPress: StemAction,
        gymLongPress: StemAction?,
    ): StemAction {
        if (!gymModeEnabled) return normalLongPress
        if (normalLongPress == StemAction.TOGGLE_GYM_MODE) return StemAction.TOGGLE_GYM_MODE
        if (gymLongPress == null) return normalLongPress
        if (gymLongPress == StemAction.TOGGLE_GYM_MODE) return StemAction.GYM_TIMER_RESET
        return gymLongPress
    }

    /**
     * True when an explicit Gym Press Actions long-press replaces this bud's Controls
     * long-press (used for the "Gym Mode overrides long press" hint on Controls).
     */
    fun gymLongPressOverridesControls(
        gymModeEnabled: Boolean,
        normalLongPress: StemAction,
        gymLongPress: StemAction?,
    ): Boolean = gymModeEnabled && gymLongPress != null &&
        normalLongPress != StemAction.TOGGLE_GYM_MODE

    /** True when Gym Press Actions long-press for this bud is owned by AirPods Controls. */
    fun isGymLongPressLockedByControls(normalLongPress: StemAction): Boolean =
        normalLongPress == StemAction.TOGGLE_GYM_MODE
}
