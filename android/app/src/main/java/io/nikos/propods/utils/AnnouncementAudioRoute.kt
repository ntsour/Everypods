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
import android.util.Log
import io.nikos.propods.services.ServiceManager

object AnnouncementAudioRoute {
    private const val TAG = "AnnouncementRoute"

    fun canAnnounceToAirPods(context: Context): Boolean {
        val connected = ServiceManager.getService()?.isConnected() == true
        if (!connected) {
            Log.d(TAG, "Skipping announcement — AirPods not connected (AACP)")
        }
        return connected
    }
}
