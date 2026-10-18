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

import android.util.Log

/**
 * Wi-Fi audio relay session flag (P2). While a relay role (source or receiver) runs in this app,
 * music takeover and lid-open autoconnect must not move the AirPods: on the Pixel that would break
 * relay output, on the source device it would pull the AirPods away from the Pixel's HR link.
 * Only our own app is gated; another EveryPods install must be stopped/Handover-off by the user.
 */
object RelaySessionState {
    const val TAG = "RelaySession"

    @Volatile var activeRole: String? = null
        private set

    val active: Boolean get() = activeRole != null

    fun begin(role: String) { activeRole = role; Log.i(TAG, "relayActive=true role=$role") }

    fun end(role: String) {
        if (activeRole == role) { activeRole = null; Log.i(TAG, "relayActive=false role=$role") }
    }

    /** Logs and returns true when [what] must be skipped because a relay is running. */
    fun blocks(what: String): Boolean {
        val role = activeRole ?: return false
        Log.i(TAG, "relayActive ($role): $what blocked")
        return true
    }
}
