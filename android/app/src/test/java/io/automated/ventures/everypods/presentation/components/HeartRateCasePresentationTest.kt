package io.automated.ventures.everypods.presentation.components

import io.automated.ventures.everypods.bluetooth.hrm.*
import org.junit.Assert.*
import org.junit.Test

class HeartRateCasePresentationTest {
    private val live = HeartRateSessionState(status = HeartRateStatus.LIVE, bpm = 82)
    private fun ready(method: HeartRateSharingMethod) = HeartRateSharingState(
        active = true, advertised = true,
        wahooDiagnostic = method == HeartRateSharingMethod.TICKR,
        bluetoothPeripheral = method == HeartRateSharingMethod.BLUETOOTH,
        port = if (method == HeartRateSharingMethod.DIRCON) 36866 else 0,
    )

    @Test fun `configured methods remain identifiable while monitoring and sharing are stopped`() {
        HeartRateSharingMethod.entries.forEach { method ->
            assertEquals(HeartRateCasePresentation(false, method, false, null),
                heartRateCasePresentation(HeartRateSessionState(), HeartRateSharingState(), method))
        }
    }

    @Test fun `live sharing shows monitoring and the actual transport with the reading`() {
        HeartRateSharingMethod.entries.forEach { method ->
            assertEquals(HeartRateCasePresentation(true, method, true, 82),
                heartRateCasePresentation(live, ready(method), null))
        }
    }

    @Test fun `unconfigured sharing shows only monitoring and any live reading`() {
        assertEquals(HeartRateCasePresentation(false, null, false, null),
            heartRateCasePresentation(HeartRateSessionState(), HeartRateSharingState(), null))
        assertEquals(HeartRateCasePresentation(true, null, false, 82),
            heartRateCasePresentation(live, HeartRateSharingState(), null))
    }

    @Test fun `transport recovery preserves fresh local reading but turns sharing off`() {
        val method = HeartRateSharingMethod.DIRCON
        val state = heartRateCasePresentation(live,
            ready(method).copy(recovering = true, error = SharingError.NETWORK_LOST), method)
        assertEquals(HeartRateCasePresentation(true, method, false, 82), state)
    }

    @Test fun `waiting stale stopped and error states never show a retained reading`() {
        HeartRateStatus.entries.filter { it != HeartRateStatus.LIVE }.forEach { status ->
            val state = heartRateCasePresentation(live.copy(status = status), ready(HeartRateSharingMethod.TICKR), null)
            assertNull(state.bpm)
            assertEquals(status == HeartRateStatus.WAITING || status == HeartRateStatus.STALE ||
                status == HeartRateStatus.RECOVERING, state.monitoring)
            assertFalse(state.sharing)
        }
    }

    @Test fun `selected method does not claim sharing until the transport is ready`() {
        assertEquals(HeartRateCasePresentation(true, HeartRateSharingMethod.BLUETOOTH, false, 82),
            heartRateCasePresentation(live, HeartRateSharingState(active = true, bluetoothPeripheral = true), HeartRateSharingMethod.BLUETOOTH))
    }

    @Test fun `active transport takes precedence over a changed saved selection`() {
        assertEquals(HeartRateSharingMethod.DIRCON,
            heartRateCasePresentation(live, ready(HeartRateSharingMethod.DIRCON), HeartRateSharingMethod.TICKR).method)
    }
}
