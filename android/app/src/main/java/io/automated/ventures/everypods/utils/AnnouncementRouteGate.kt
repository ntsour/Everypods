package io.automated.ventures.everypods.utils

import java.util.Locale

/**
 * Pure (Android-free) decision: may a spoken announcement play on the current
 * output route?  Every announcement path (system TTS, ElevenLabs, notifications,
 * calls, low-battery, gym timer) goes through this via [AnnouncementAudioRoute];
 * only the settings voice preview bypasses it.
 *
 * Rules (1.0.4):
 *  - Default: announce only when the route is the user's managed AirPods,
 *    matched by Bluetooth address (saved `mac_address`), not "any BT device".
 *    AirPods connected but the route on another device (car took A2DP) => skip.
 *  - "Also announce on other Bluetooth devices" ON: any Bluetooth output route.
 *  - Never the phone speaker / earpiece, wired or USB outputs.
 *  - Without BLUETOOTH_CONNECT the route address cannot be trusted (Android
 *    anonymises it), so the AirPods-only default skips.
 *
 * Runtime decisions are logged to [TAG] ("AnnounceGate"), so
 * `adb logcat -s AnnounceGate` shows every announce/skip with its reason.
 */
object AnnouncementRouteGate {
    const val TAG = "AnnounceGate"

    enum class OutputKind(val label: String, val isBluetooth: Boolean, val isPhoneBuiltIn: Boolean = false) {
        BT_A2DP("bt_a2dp", true),
        BT_SCO("bt_sco", true),
        BLE_HEADSET("ble_headset", true),
        BLE_SPEAKER("ble_speaker", true),
        BLE_BROADCAST("ble_broadcast", true),
        HEARING_AID("hearing_aid", true),
        BUILTIN_SPEAKER("speaker", false, isPhoneBuiltIn = true),
        BUILTIN_EARPIECE("earpiece", false, isPhoneBuiltIn = true),
        WIRED("wired", false),
        USB("usb", false),
        OTHER("other", false),
    }

    data class Output(val kind: OutputKind, val address: String?, val name: String?)

    data class Input(
        /** Devices the announcement audio would be routed to right now; null = unreadable. */
        val route: List<Output>?,
        /** Bluetooth address of the AirPods EveryPods manages (prefs `mac_address`). */
        val managedAirPodsAddress: String?,
        val hasBluetoothConnect: Boolean,
        val alsoAnnounceOnOtherBluetooth: Boolean,
        val isPreview: Boolean = false,
    )

    data class Decision(val announce: Boolean, val reason: String) {
        /** One log line in the style of the Startup gate logging. */
        val logLine: String get() = (if (announce) "Announce ok: " else "Announce skip: ") + reason
    }

    fun decide(input: Input): Decision {
        if (input.isPreview) return Decision(true, "settings voice preview (route gate bypassed)")

        val route = input.route
        if (route.isNullOrEmpty()) return Decision(false, "route=unknown (no output device reported)")
        val routeDesc = describe(route)

        // Never leak speech through the phone itself or a non-Bluetooth output.
        if (route.any { it.kind.isPhoneBuiltIn }) {
            return Decision(false, "route=$routeDesc not AirPods (phone speaker)")
        }
        val bluetooth = route.filter { it.kind.isBluetooth }
        if (bluetooth.isEmpty()) return Decision(false, "route=$routeDesc not AirPods")

        if (input.alsoAnnounceOnOtherBluetooth) {
            return Decision(true, "route=$routeDesc (other Bluetooth devices allowed)")
        }
        if (!input.hasBluetoothConnect) {
            return Decision(false, "route=$routeDesc cannot verify AirPods (BLUETOOTH_CONNECT not granted)")
        }
        val managed = normalizeAddress(input.managedAirPodsAddress)
            ?: return Decision(false, "route=$routeDesc not AirPods (no managed AirPods address saved)")
        return if (bluetooth.any { normalizeAddress(it.address) == managed }) {
            Decision(true, "route=$routeDesc is managed AirPods")
        } else {
            Decision(false, "route=$routeDesc not AirPods (managed=${maskAddress(managed)})")
        }
    }

    /** Uppercase colon MAC, or null if blank/anonymised ("XX:XX:XX:XX:AB:CD") / malformed. */
    fun normalizeAddress(address: String?): String? {
        val a = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (a.isEmpty() || a.contains("XX")) return null
        if (!MAC_REGEX.matches(a)) return null
        if (a == "00:00:00:00:00:00" || a == "02:00:00:00:00:00") return null
        return a
    }

    fun describe(route: List<Output>): String = route.joinToString("+") { out ->
        val name = out.name?.trim().takeUnless { it.isNullOrEmpty() }
        val addr = normalizeAddress(out.address)?.let(::maskAddress)
        buildString {
            append(out.kind.label)
            if (name != null) append('/').append(name)
            if (out.kind.isBluetooth && addr != null) append('[').append(addr).append(']')
        }
    }

    /** Keep logs useful without printing full MACs: "…AB:CD". */
    fun maskAddress(address: String): String = "…" + address.takeLast(5)

    private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
}
