package io.automated.ventures.everypods.utils

/**
 * Tracks when Bluetooth turned on. When the adapter comes up, Android binds every media
 * browser service for AVRCP and some apps briefly create and drop an AudioTrack. That
 * looks like playback but isn't, so media takeovers are held off while it settles.
 *
 * The observed storm lasted about 2.1 s (12:47:21.6 to 12:47:23.7); 6 s leaves roughly
 * 3x margin on slower devices without delaying a real press of play for long.
 */
class BluetoothOnSettle(private val windowMs: Long = WINDOW_MS) {
    private var turnedOnAt: Long? = null

    fun onTurnedOn(now: Long) {
        turnedOnAt = now
    }

    fun settling(now: Long): Boolean {
        val at = turnedOnAt ?: return false
        return now - at in 0 until windowMs
    }

    companion object {
        const val WINDOW_MS = 6_000L
    }
}
