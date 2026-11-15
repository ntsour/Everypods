/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

/** Only player operations belong on the sender; AirPods and output-volume controls stay local. */
internal data class RelayPlaybackCommand(
    val action: Action,
    val force: Boolean = false,
    val replayWhenPaused: Boolean = false,
) {
    enum class Action(val wire: Byte) {
        PLAY_PAUSE(1), PREVIOUS(2), NEXT(3), PAUSE(4), PLAY(5),
    }

    init {
        require(!force || action == Action.PAUSE || action == Action.PLAY)
        require(!replayWhenPaused || action == Action.PLAY)
    }

    fun encode(): ByteArray = byteArrayOf(
        RelaySessionControl.PLAYBACK_COMMAND,
        action.wire,
        ((if (force) 1 else 0) or (if (replayWhenPaused) 2 else 0)).toByte(),
    )

    companion object {
        fun decode(packet: ByteArray): RelayPlaybackCommand? {
            if (packet.size != 3 || packet[0] != RelaySessionControl.PLAYBACK_COMMAND) return null
            val action = Action.entries.firstOrNull { it.wire == packet[1] } ?: return null
            val flags = packet[2].toInt()
            if (flags !in 0..3) return null
            return runCatching { RelayPlaybackCommand(action, flags and 1 != 0, flags and 2 != 0) }.getOrNull()
        }
    }
}
