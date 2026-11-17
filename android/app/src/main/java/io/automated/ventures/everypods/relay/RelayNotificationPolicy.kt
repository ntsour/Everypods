/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.pm.ServiceInfo

/** Decides when the relay needs its own "Playback" notification. */
internal object RelayNotificationPolicy {
    private val busyStages = setOf(
        RelayStage.CONNECTING, RelayStage.VERIFYING, RelayStage.WAITING_FOR_AUDIO,
        RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED, RelayStage.FAILED,
    )

    /**
     * True when the relay would only say "Ready to receive audio": nothing is
     * streaming, connecting, failing, or waiting for the user to approve a switch.
     */
    fun isIdle(state: RelayUiState): Boolean =
        state.stage !in busyStages && state.reason == null && state.pendingSenderMac == null

    /** Drops media foreground types whose capture or playback has already stopped. */
    fun liveForegroundType(requested: Int, capturing: Boolean, playing: Boolean): Int {
        var type = requested or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (!capturing) type = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.inv()
        if (!playing) type = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK.inv()
        return type
    }
}
