/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

// Error enums are rendered through localized UI strings; no exception text in product UI.
enum class SharingError { WIFI_REQUIRED, PERMISSION, NETWORK_LOST, ADVERTISEMENT, SERVER, BLUETOOTH_UNSUPPORTED, BLUETOOTH_PERMISSION, BLUETOOTH_GATT, BLUETOOTH_ADVERTISING, WAHOO_UNAVAILABLE }
data class HeartRateSharingState(
    val active: Boolean = false, val advertised: Boolean = false,
    val name: String = "EveryPods HR", val port: Int = 0,
    val clients: Int = 0, val subscriptions: Int = 0,
    val requests: Long = 0, val sent: Long = 0, val wahooDiagnostic: Boolean = false,
    val bluetoothPeripheral: Boolean = false,
    val recovering: Boolean = false,
    val error: SharingError? = null
)
