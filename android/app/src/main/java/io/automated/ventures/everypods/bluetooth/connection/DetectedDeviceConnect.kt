package io.automated.ventures.everypods.bluetooth.connection

/**
 * Opens the control socket for a device reported by AIRPODS_CONNECTION_DETECTED, from a
 * background coroutine that runs after the broadcast receiver has returned.
 *
 * The service's `device` field is mutable and written on the main thread: an
 * AIRPODS_DISCONNECTED that lands in between (a peer taking the AirPods right after they
 * connect here) sets it to null. Reading the field again in the coroutine with `!!` crashed
 * the app (1.0.8, 2026-11-25). So the caller captures the detected device up front, and the
 * connect is skipped when the current device has since been cleared or replaced.
 *
 * @return true if [connect] was called.
 */
internal fun <D : Any> connectIfStillCurrent(
    detected: D,
    current: () -> D?,
    connect: (D) -> Unit,
): Boolean {
    if (current() != detected) return false
    connect(detected)
    return true
}
