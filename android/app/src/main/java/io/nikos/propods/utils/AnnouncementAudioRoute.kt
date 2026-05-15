/*
    ProPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 ProPods contributors

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

package io.nikos.propods.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.util.Log

object AnnouncementAudioRoute {
    private const val TAG = "AnnouncementRoute"

    fun canAnnounceToAirPods(context: Context): Boolean = canAnnounceToBluetoothAudio(context)

    fun canAnnounceToBluetoothAudio(context: Context): Boolean {
        val appContext = context.applicationContext
        val audioManager = appContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaRouter = appContext
            .getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

        val selectedRoute = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val selectedRouteIsBluetooth =
            selectedRoute?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH

        val availableBluetoothOutputs = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isBluetoothAudioSink() }

        if (!selectedRouteIsBluetooth) {
            Log.d(
                TAG,
                "Skipping announcement: selected media route is not Bluetooth " +
                    "(selected=${selectedRoute?.name}:${selectedRoute?.deviceType}, " +
                    "btOutputs=${availableBluetoothOutputs.map { it.routeLabel() }})"
            )
        }
        return selectedRouteIsBluetooth && availableBluetoothOutputs.isNotEmpty()
    }

    private fun AudioDeviceInfo.isBluetoothAudioSink(): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_HEARING_AID
    }

    private fun AudioDeviceInfo.routeLabel(): String {
        return "${productName ?: "unknown"}:${type}"
    }
}
