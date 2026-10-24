/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

/** Combines independent sensor and transport owners into the stable service/UI state. */
object HeartRateSharingStateReducer {
    fun reconcile(
        state: HeartRateSharingState,
        intentRequested: Boolean,
        sensor: HeartRateSessionState,
        sensorRecoveryActive: Boolean = false,
    ): HeartRateSharingState {
        val sensorPhase = when {
            sensorRecoveryActive || sensor.status == HeartRateStatus.RECOVERING || (intentRequested && sensor.failure in setOf(
                HeartRateFailure.NO_DATA, HeartRateFailure.CONNECTION_LOST,
            )) -> HeartRateSensorPhase.RECOVERING
            sensor.failure == HeartRateFailure.NOT_OWNER -> HeartRateSensorPhase.NOT_OWNER
            sensor.status == HeartRateStatus.LIVE -> HeartRateSensorPhase.LIVE
            sensor.status == HeartRateStatus.STALE -> HeartRateSensorPhase.STALE
            sensor.status == HeartRateStatus.ERROR -> HeartRateSensorPhase.FAILED
            else -> HeartRateSensorPhase.WAITING
        }
        val transportPhase = when {
            state.transportPhase != HeartRateTransportPhase.STOPPED -> state.transportPhase
            !state.active && state.error != null -> HeartRateTransportPhase.ACTION_REQUIRED
            !state.active -> HeartRateTransportPhase.STOPPED
            state.error == SharingError.PERMISSION || state.error == SharingError.PORT_IN_USE ||
                state.error == SharingError.UNSUPPORTED_NETWORK -> HeartRateTransportPhase.ACTION_REQUIRED
            state.advertised -> HeartRateTransportPhase.DISCOVERABLE
            state.recovering -> HeartRateTransportPhase.RETRYING
            else -> HeartRateTransportPhase.STARTING
        }
        return state.copy(
            intent = if (intentRequested) HeartRateShareIntent.REQUESTED else HeartRateShareIntent.STOPPED,
            sensorPhase = sensorPhase,
            transportPhase = transportPhase,
        )
    }
}
