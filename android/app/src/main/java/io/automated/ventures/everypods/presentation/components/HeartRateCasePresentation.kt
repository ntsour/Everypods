/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSessionState
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateStatus

internal data class HeartRateCasePresentation(
    val monitoring: Boolean,
    val method: HeartRateSharingMethod?,
    val sharing: Boolean,
    val bpm: Int?,
)

/** Sensor and transport have independent indicators; stale readings are never presented as live. */
internal fun heartRateCasePresentation(
    sensor: HeartRateSessionState,
    transport: HeartRateSharingState,
    configuredMethod: HeartRateSharingMethod?,
): HeartRateCasePresentation {
    val method = when {
        transport.active && transport.wahooDiagnostic -> HeartRateSharingMethod.TICKR
        transport.active && transport.bluetoothPeripheral -> HeartRateSharingMethod.BLUETOOTH
        transport.active && transport.port > 0 -> HeartRateSharingMethod.DIRCON
        else -> configuredMethod
    }
    return HeartRateCasePresentation(
        monitoring = sensor.running || sensor.status == HeartRateStatus.RECOVERING,
        method = method,
        sharing = sensor.status == HeartRateStatus.LIVE && transport.active && !transport.recovering && transport.error == null &&
            (transport.wahooDiagnostic || transport.advertised),
        bpm = sensor.bpm.takeIf { sensor.running && sensor.status == HeartRateStatus.LIVE },
    )
}
