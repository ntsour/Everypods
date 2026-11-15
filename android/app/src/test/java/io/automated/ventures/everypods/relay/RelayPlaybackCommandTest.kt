package io.automated.ventures.everypods.relay

import org.junit.Assert.*
import org.junit.Test

class RelayPlaybackCommandTest {
    @Test fun preservesEverySupportedCommandAndPlaybackFlag() {
        val commands = RelayPlaybackCommand.Action.entries.map { RelayPlaybackCommand(it) } + listOf(
            RelayPlaybackCommand(RelayPlaybackCommand.Action.PAUSE, force = true),
            RelayPlaybackCommand(RelayPlaybackCommand.Action.PLAY, force = true),
            RelayPlaybackCommand(RelayPlaybackCommand.Action.PLAY, replayWhenPaused = true),
            RelayPlaybackCommand(RelayPlaybackCommand.Action.PLAY, force = true, replayWhenPaused = true),
        )
        commands.forEach { assertEquals(it, RelayPlaybackCommand.decode(it.encode())) }
    }

    @Test fun rejectsUnknownActionsMalformedPacketsAndInvalidFlags() {
        listOf(
            byteArrayOf(), byteArrayOf(19), byteArrayOf(19, 1), byteArrayOf(19, 1, 0, 0),
            byteArrayOf(18, 1, 0), byteArrayOf(19, 99, 0), byteArrayOf(19, 1, 1),
            byteArrayOf(19, 2, 2), byteArrayOf(19, 3, 3), byteArrayOf(19, 4, 2),
            byteArrayOf(19, 5, 4), byteArrayOf(19, 5, -1),
        ).forEach { assertNull(it.contentToString(), RelayPlaybackCommand.decode(it)) }
    }
}
