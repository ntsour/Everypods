/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import io.automated.ventures.everypods.opus.NativeOpusCodec

/** Encodes one captured 20 ms PCM frame with the versioned sequence/timestamp envelope. */
class RelayOpusFramer(private val codec: NativeOpusCodec) {
    private var nextSequence = 0L

    @Synchronized
    fun encode(pcm: ShortArray, timestampNanos: Long): ByteArray {
        require(timestampNanos >= 0)
        val packet = RelayMediaPacket(
            sequence = nextSequence,
            timestampNanos = timestampNanos,
            durationMillis = codec.frameDurationMs,
            opusPayload = codec.encode(pcm),
        )
        nextSequence = (nextSequence + 1) and 0xffff_ffffL
        return packet.encode()
    }

    @Synchronized
    fun decode(encoded: ByteArray): ShortArray = codec.decode(RelayMediaPacket.decode(encoded, codec.frameDurationMs).opusPayload)

    /** Request Opus packet-loss concealment for a frame the jitter buffer declared missing. */
    @Synchronized
    fun concealLoss(): ShortArray = codec.decode(null)

    @Synchronized
    fun resetSequence() {
        nextSequence = 0L
    }
}
