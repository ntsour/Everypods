/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayNotificationPolicyTest {
    @Test fun readyReceiverIsIdle() {
        assertTrue(RelayNotificationPolicy.isIdle(RelayUiState()))
        assertTrue(RelayNotificationPolicy.isIdle(RelayUiState(role = RelayRole.RECEIVER, stage = RelayStage.WAITING_FOR_SOURCE)))
        assertTrue(RelayNotificationPolicy.isIdle(RelayUiState(stage = RelayStage.TRUSTED)))
    }

    @Test fun activeOrFailingRelayNeedsItsOwnNotification() {
        listOf(RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING,
            RelayStage.BUFFERING, RelayStage.SUSPENDED, RelayStage.FAILED).forEach { stage ->
            assertFalse(stage.name, RelayNotificationPolicy.isIdle(RelayUiState(role = RelayRole.RECEIVER, stage = stage)))
        }
    }

    @Test fun stopReasonOrPendingSwitchStaysVisible() {
        assertFalse(RelayNotificationPolicy.isIdle(RelayUiState(stage = RelayStage.STOPPED, reason = "Network lost")))
        assertFalse(RelayNotificationPolicy.isIdle(RelayUiState(stage = RelayStage.WAITING_FOR_SOURCE, pendingSenderMac = "AA:BB:CC:DD:EE:FF")))
    }

    @Test fun endedCaptureOrPlaybackIsNotReclaimed() {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val playback = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        assertEquals(device, RelayNotificationPolicy.liveForegroundType(device or projection, capturing = false, playing = false))
        assertEquals(device or projection, RelayNotificationPolicy.liveForegroundType(device or projection, capturing = true, playing = false))
        assertEquals(device, RelayNotificationPolicy.liveForegroundType(device or playback, capturing = false, playing = false))
        assertEquals(device or playback, RelayNotificationPolicy.liveForegroundType(device or playback, capturing = false, playing = true))
    }
}
