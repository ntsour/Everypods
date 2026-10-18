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
import android.content.pm.PackageManager
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import java.util.UUID

/**
 * Coexistence gate for the Wi-Fi audio relay dev build (`.relaydev`, debug-derived types).
 *
 * A dev build installs next to production EveryPods. Two apps on one phone must not both own
 * the AirPods AACP L2CAP link, music takeover, A2DP connect or lid-open autoconnect, and the dev
 * build must not peer or lease with production over the CrossDevice channel. So in a dev build
 * those features are OFF by default ("relay-only") and only a dev switch re-enables them.
 * Production builds ([BuildConfig.RELAY_DEV] false) are never affected.
 */
object RelayDevMode {
    const val TAG = "RelayDev"
    const val PREFS_NAME = "settings"
    const val KEY_ENABLE_AACP = "relaydev_enable_aacp"
    const val PRODUCTION_PACKAGE = "io.automated.ventures.everypods"

    val isDevBuild: Boolean get() = BuildConfig.RELAY_DEV

    /** Pure decision, unit-tested: production always on; dev only with an explicit opt-in. */
    fun aacpAllowed(relayDev: Boolean, devOptIn: Boolean): Boolean = !relayDev || devOptIn

    /** Whether AACP, takeover, autoconnect, lid features and the CrossDevice peer may run. */
    fun aacpEnabled(context: Context): Boolean {
        if (!isDevBuild) return true
        val optIn = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLE_AACP, false)
        return aacpAllowed(true, optIn)
    }

    fun setAacpEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLE_AACP, enabled).apply()
        Log.w(TAG, "dev AACP switch set enabled=$enabled (restart app to apply)")
    }

    /** Logs and returns true when [what] must be skipped in this build. */
    fun blocks(context: Context, what: String): Boolean {
        if (aacpEnabled(context)) return false
        Log.i(TAG, "relay-only dev build: $what disabled (AACP/autoconnect/takeover off)")
        return true
    }

    /** Warn when the other app is present: production next to dev. */
    fun shouldWarn(relayDev: Boolean, ownPackage: String, productionInstalled: Boolean): Boolean =
        relayDev && ownPackage != PRODUCTION_PACKAGE && productionInstalled

    fun isProductionInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PRODUCTION_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    val crossDeviceUuid: UUID get() = UUID.fromString(BuildConfig.CROSS_DEVICE_UUID)
    val crossDeviceServiceName: String get() = BuildConfig.CROSS_DEVICE_SERVICE_NAME

    fun logStartup(context: Context) {
        if (!isDevBuild) return
        Log.w(
            TAG,
            "dev build pkg=${context.packageName} aacpEnabled=${aacpEnabled(context)} " +
                "productionInstalled=${isProductionInstalled(context)} crossDeviceUuid=$crossDeviceUuid"
        )
    }
}
