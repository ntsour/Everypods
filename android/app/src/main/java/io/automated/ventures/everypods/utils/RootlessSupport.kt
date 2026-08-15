/*
    EveryPods - AirPods liberated from Apple’s ecosystem
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

import android.os.Build

// Returns true when the device can in principle open the AACP L2CAP socket to the
// AirPods. False means the device runs the same UI but every AACP-controlled
// feature is greyed out (battery, handover, BLE-based features still work).
//
// Android 17 (SDK 37): Google confirmed the L2CAP fix is required platform-wide,
// so all OEMs must ship it. Confirmed working on OneUI 9 (Samsung Galaxy S25).
// We trust all SDK ≥ 37 devices wholesale.
//
// Android 16 (SDK 36): fix was available on Pixel and OnePlus/OPPO only.
// Devices outside these checks fall back to limited mode: the UI stays up but
// AACP-controlled features are greyed out while BLE-based features still work.
fun isAacpCapable(): Boolean {
    val mfr = Build.MANUFACTURER.lowercase()
    val sdk = Build.VERSION.SDK_INT
    return when {
        sdk >= 37 -> true                                // Android 17+: all OEMs
        mfr == "google" -> sdk >= 36
        mfr in listOf("oneplus", "oppo") -> sdk >= 36
        else -> false
    }
}
