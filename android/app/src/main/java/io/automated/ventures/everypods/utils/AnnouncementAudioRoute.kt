/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

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

package io.automated.ventures.everypods.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager

object AnnouncementAudioRoute {
    private const val TAG = "AnnouncementRoute"

    fun canAnnounceToAirPods(context: Context): Boolean {
        // AACP connection is the primary indicator on devices that support it.
        if (ServiceManager.getService()?.isConnected() == true) return true

        // Fallback for AACP-incapable devices (e.g. Xiaomi/MIUI): check whether
        // a Bluetooth A2DP output is currently active. TTS audio will route there.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasBtA2dp = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (!hasBtA2dp) {
            Log.d(TAG, "Skipping announcement — no BT A2DP output and no AACP connection")
        }
        return hasBtA2dp
    }
}
