package io.automated.ventures.everypods.relay

import org.junit.Assert.*
import org.junit.Test

class RelayHandoverPolicyTest {
    @Test fun sendingSetupPlaybackAndBufferingSuppressAutomaticTakeover() {
        listOf(RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING).forEach {
            assertTrue(RelayHandoverPolicy.suppressAutomaticHandover(true, RelayUiState(role = RelayRole.SOURCE, stage = it)))
        }
    }
    @Test fun stopReleasesSuppressionImmediatelyEvenWhileWaitingForRemoteAcknowledgement() {
        assertFalse(RelayHandoverPolicy.suppressAutomaticHandover(false, RelayUiState(role = RelayRole.SOURCE, stage = RelayStage.STREAMING)))
    }
    @Test fun stoppedFailedAndReceivingSessionsNeverSuppressLocalHandover() {
        listOf(RelayStage.STOPPED, RelayStage.FAILED, RelayStage.TRUSTED).forEach {
            assertFalse(RelayHandoverPolicy.suppressAutomaticHandover(true, RelayUiState(role = RelayRole.SOURCE, stage = it)))
        }
        assertFalse(RelayHandoverPolicy.suppressAutomaticHandover(true, RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.STREAMING)))
    }
}
