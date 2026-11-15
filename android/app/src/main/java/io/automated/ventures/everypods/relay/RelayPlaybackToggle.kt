/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import java.nio.ByteBuffer

/** Correlates source playback decisions with a receiver's pending listening-mode action. */
internal class RelayPlaybackToggle {
    private data class Pending(val peer: String, val id: Long, val callback: (Boolean) -> Unit)
    private var nextId = 0L
    private var pending: Pending? = null

    fun request(peer: String, callback: (Boolean) -> Unit): ByteArray? {
        // Do not issue another toggle before the first decision arrives.
        if (pending != null) return null
        val id = ++nextId
        pending = Pending(peer, id, callback)
        return ByteBuffer.allocate(9).put(RelaySessionControl.PLAYBACK_TOGGLE).putLong(id).array()
    }

    fun complete(peer: String, packet: ByteArray): Boolean {
        if (packet.size != 10 || packet[0] != RelaySessionControl.PLAYBACK_TOGGLE_RESULT ||
            packet[9].toInt() !in 0..1) return false
        val current = pending ?: return false
        if (!current.peer.equals(peer, ignoreCase = true) || ByteBuffer.wrap(packet, 1, 8).long != current.id) return false
        pending = null
        current.callback(packet[9].toInt() == 1)
        return true
    }

    fun clear() { pending = null }

    companion object {
        fun result(request: ByteArray, wasPlaying: Boolean): ByteArray? {
            if (request.size != 9 || request[0] != RelaySessionControl.PLAYBACK_TOGGLE) return null
            return request.copyOf(10).also {
                it[0] = RelaySessionControl.PLAYBACK_TOGGLE_RESULT
                it[9] = if (wasPlaying) 1 else 0
            }
        }

        fun receivesPlaybackControls(state: RelayUiState): Boolean =
            state.role == RelayRole.RECEIVER && state.stage in setOf(
                RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED,
            )
    }
}
