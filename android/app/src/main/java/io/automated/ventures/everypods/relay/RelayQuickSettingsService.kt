/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.automated.ventures.everypods.R

/** Quick entry to the relay Home surface; Stop applies only to a session on this device. */
class RelayQuickSettingsService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val active = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("audio_relay_local_active", false)
        if (active) {
            ContextCompat.startForegroundService(
                this,
                Intent().setClassName(this, "io.automated.ventures.everypods.relay.RelayPreviewService")
                    .setAction("io.automated.ventures.everypods.relay.STOP"),
            )
        } else {
            val intent = Intent().setClassName(this, "io.automated.ventures.everypods.MainActivity")
                .putExtra(RelayPreviewService.EXTRA_OPEN_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
        update()
    }

    private fun update() {
        val active = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("audio_relay_local_active", false)
        qsTile?.apply {
            label = getString(if (active) R.string.audio_relay_stop else R.string.audio_relay_relay_home_title)
            subtitle = when {
                active && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("audio_relay_local_suspended", false) -> getString(R.string.audio_relay_suspended)
                active -> getString(R.string.audio_relay_streaming)
                else -> getString(R.string.audio_relay_start)
            }
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            icon = Icon.createWithResource(this@RelayQuickSettingsService, android.R.drawable.ic_media_play)
            updateTile()
        }
    }
}
