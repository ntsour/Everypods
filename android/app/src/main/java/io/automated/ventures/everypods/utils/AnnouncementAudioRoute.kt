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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager

/**
 * Android side of the announcement route gate. Reads the current output route
 * for announcement audio, the managed AirPods address and the user's
 * "Also announce on other Bluetooth devices" switch, then defers to the pure
 * [AnnouncementRouteGate.decide]. Never throws: any failure means skip.
 *
 * Before 1.0.4 this returned true when the AACP link was up OR when any
 * Bluetooth A2DP device was merely *connected* (not necessarily the active
 * route), so speech could go to a car/speaker, or to the phone speaker while
 * the AirPods' AACP link was up but audio was routed elsewhere.
 */
object AnnouncementAudioRoute {
    private const val TAG = AnnouncementRouteGate.TAG

    /**
     * True if a spoken announcement may play now. [isPreview] is only for the
     * settings voice preview, which must always play so users can test.
     */
    fun canAnnounceToAirPods(context: Context, isPreview: Boolean = false): Boolean {
        val decision = try {
            AnnouncementRouteGate.decide(readInput(context.applicationContext, isPreview))
        } catch (t: Throwable) {
            AnnouncementRouteGate.Decision(false, "route check failed (${t.javaClass.simpleName}: ${t.message})")
        }
        Log.i(TAG, decision.logLine)
        return decision.announce
    }

    private fun readInput(ctx: Context, isPreview: Boolean): AnnouncementRouteGate.Input {
        val hasBtConnect = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        return AnnouncementRouteGate.Input(
            route = if (isPreview) emptyList() else currentRoute(ctx),
            managedAirPodsAddress = managedAirPodsAddress(ctx),
            hasBluetoothConnect = hasBtConnect,
            alsoAnnounceOnOtherBluetooth = AnnouncementPrefs.alsoAnnounceOnOtherBluetooth(ctx),
            isPreview = isPreview,
        )
    }

    /** Devices the announcement audio would be routed to right now (API 33+). */
    private fun currentRoute(ctx: Context): List<AnnouncementRouteGate.Output>? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val speech = AnnouncementAudioAttributes.speech(ctx)
        var devices = runCatching { am.getAudioDevicesForAttributes(speech) }.getOrNull().orEmpty()
        if (devices.isEmpty()) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            devices = runCatching { am.getAudioDevicesForAttributes(media) }.getOrNull().orEmpty()
        }
        if (devices.isEmpty()) return null
        return devices.map { info ->
            AnnouncementRouteGate.Output(
                kind = kindOf(info.type),
                address = runCatching { info.address }.getOrNull(),
                name = runCatching { info.productName?.toString() }.getOrNull(),
            )
        }
    }

    private fun managedAirPodsAddress(ctx: Context): String? {
        val live = runCatching { ServiceManager.getService()?.macAddress }.getOrNull()
        if (!live.isNullOrBlank()) return live
        return AnnouncementPrefs.prefs(ctx).getString("mac_address", null)
    }

    private fun kindOf(type: Int): AnnouncementRouteGate.OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AnnouncementRouteGate.OutputKind.BT_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AnnouncementRouteGate.OutputKind.BT_SCO
        AudioDeviceInfo.TYPE_BLE_HEADSET -> AnnouncementRouteGate.OutputKind.BLE_HEADSET
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> AnnouncementRouteGate.OutputKind.BLE_SPEAKER
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> AnnouncementRouteGate.OutputKind.BLE_BROADCAST
        AudioDeviceInfo.TYPE_HEARING_AID -> AnnouncementRouteGate.OutputKind.HEARING_AID
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AnnouncementRouteGate.OutputKind.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AnnouncementRouteGate.OutputKind.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> AnnouncementRouteGate.OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AnnouncementRouteGate.OutputKind.USB
        else -> AnnouncementRouteGate.OutputKind.OTHER
    }
}
