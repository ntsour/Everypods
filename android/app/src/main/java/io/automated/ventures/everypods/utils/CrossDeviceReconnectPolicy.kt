package io.automated.ventures.everypods.utils

/** Keeps passive peer recovery quiet while the local phone is carrying audio. */
object CrossDeviceReconnectPolicy {
    /** A held-headset connection attempt must be explicitly requested by the user. */
    fun mayAttempt(
        localAirPodsConnected: Boolean,
        manualAttemptRequested: Boolean,
    ): Boolean = !localAirPodsConnected || manualAttemptRequested
}
