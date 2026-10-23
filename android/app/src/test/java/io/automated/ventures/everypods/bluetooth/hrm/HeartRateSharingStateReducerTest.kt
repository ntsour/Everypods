package io.automated.ventures.everypods.bluetooth.hrm

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateSharingStateReducerTest {
    @Test fun sensorAndTransportRecoveryAreIndependentAndClearOnRecovery() {
        val recoveringTransport = HeartRateSharingStateReducer.reconcile(
            HeartRateSharingState(active = true, recovering = true),
            intentRequested = true,
            sensor = HeartRateSessionState(status = HeartRateStatus.LIVE, bpm = 120),
        )
        assertEquals(HeartRateSensorPhase.LIVE, recoveringTransport.sensorPhase)
        assertEquals(HeartRateTransportPhase.RETRYING, recoveringTransport.transportPhase)

        val recovered = HeartRateSharingStateReducer.reconcile(
            recoveringTransport.copy(
                recovering = false,
                advertised = true,
                transportPhase = HeartRateTransportPhase.DISCOVERABLE,
            ),
            intentRequested = true,
            sensor = HeartRateSessionState(status = HeartRateStatus.LIVE, bpm = 121),
        )
        assertEquals(HeartRateSensorPhase.LIVE, recovered.sensorPhase)
        assertEquals(HeartRateTransportPhase.DISCOVERABLE, recovered.transportPhase)
    }

    @Test fun permissionAndOwnershipErrorsRemainTyped() {
        val permission = HeartRateSharingStateReducer.reconcile(
            HeartRateSharingState(error = SharingError.PERMISSION), false,
            HeartRateSessionState(status = HeartRateStatus.WAITING),
        )
        assertEquals(HeartRateTransportPhase.ACTION_REQUIRED, permission.transportPhase)
        assertEquals(HeartRateShareIntent.STOPPED, permission.intent)

        val notOwner = HeartRateSharingStateReducer.reconcile(
            HeartRateSharingState(active = true), true,
            HeartRateSessionState(status = HeartRateStatus.ERROR, failure = HeartRateFailure.NOT_OWNER),
        )
        assertEquals(HeartRateSensorPhase.NOT_OWNER, notOwner.sensorPhase)
        assertEquals(HeartRateShareIntent.REQUESTED, notOwner.intent)
    }

    @Test fun sensorStaleAndRecoveryPhasesDoNotInventSamples() {
        val stale = HeartRateSharingStateReducer.reconcile(
            HeartRateSharingState(active = true, advertised = true), true,
            HeartRateSessionState(status = HeartRateStatus.STALE, bpm = null),
        )
        assertEquals(HeartRateSensorPhase.STALE, stale.sensorPhase)

        val recovering = HeartRateSharingStateReducer.reconcile(
            stale, true,
            HeartRateSessionState(status = HeartRateStatus.ERROR, failure = HeartRateFailure.NO_DATA),
        )
        assertEquals(HeartRateSensorPhase.RECOVERING, recovering.sensorPhase)
    }
}
