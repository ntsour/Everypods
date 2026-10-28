/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Existing Wi-Fi/hotspot sockets need LAN access, not Wi-Fi management or location access. */
object RelayNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    fun required(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 37
    fun granted(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean = !required(sdk) ||
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
