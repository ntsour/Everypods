/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.utils.GymModePrefs
import me.kavishdevar.librepods.utils.GymTimer

@RequiresApi(Build.VERSION_CODES.Q)
class GymModeQSService : TileService() {

    private lateinit var sharedPreferences: SharedPreferences
    private var isAirPodsConnected: Boolean = false

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.AIRPODS_CONNECTED -> {
                    isAirPodsConnected = true
                    updateTile()
                }
                AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                    isAirPodsConnected = false
                    updateTile()
                }
            }
        }
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "gym_mode_enabled") {
            updateTile()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    override fun onStartListening() {
        super.onStartListening()
        val service = ServiceManager.getService()
        isAirPodsConnected = service?.isConnected() == true

        val availabilityIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(availabilityReceiver, availabilityIntentFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(availabilityReceiver, availabilityIntentFilter)
            }
            sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        } catch (e: Exception) {
            Log.e("GymModeQSService", "Error registering receivers: $e")
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            unregisterReceiver(availabilityReceiver)
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        } catch (e: Exception) {
            Log.e("GymModeQSService", "Error unregistering receivers: $e")
        }
    }

    override fun onClick() {
        super.onClick()
        if (!isAirPodsConnected) return

        val current = GymModePrefs.isEnabled(this)
        GymModePrefs.setEnabled(this, !current)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = GymModePrefs.isEnabled(this)

        if (isAirPodsConnected) {
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "Gym Mode"
            val subtitle = if (enabled) {
                val e = GymTimer.elapsedMs() / 1000
                if (GymTimer.state() == GymTimer.State.RUNNING) String.format("Running %02d:%02d", e / 60, e % 60)
                else "Stopwatch ready"
            } else "Tap to enable"
            tile.subtitle = subtitle
            tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Gym Mode"
            tile.subtitle = "Disconnected"
            tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        }

        try {
            tile.updateTile()
        } catch (e: Exception) {
            Log.e("GymModeQSService", "Error updating tile: $e")
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d("GymModeQSService", "Tile added")
    }
}
