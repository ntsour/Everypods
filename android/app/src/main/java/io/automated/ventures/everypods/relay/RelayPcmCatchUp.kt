/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import io.automated.ventures.everypods.relay.transport.RelayJitterBuffer

/** Occasional one-frame overlap removes delay without a hard PCM splice or decoder reset. */
object RelayPcmCatchUp {
    fun decode(frame: RelayJitterBuffer.Output.Frame, channels: Int, decode: (ByteArray) -> ShortArray): ShortArray {
        // Opus prediction must advance through BOTH packets, including the one being shortened.
        val earlier = frame.catchUpFrom?.let { decode(it.opusPayload) }
        val later = decode(frame.packet.opusPayload)
        if (earlier == null) return later
        require(channels > 0 && earlier.size == later.size && later.size % channels == 0)
        val samplesPerChannel = later.size / channels
        require(samplesPerChannel > 1)
        val denominator = samplesPerChannel - 1L
        for (sample in 0 until samplesPerChannel) {
            for (channel in 0 until channels) {
                val index = sample * channels + channel
                // Shared stereo weights preserve channel alignment; convex mixing cannot clip.
                later[index] = ((earlier[index].toLong() * (denominator - sample) +
                    later[index].toLong() * sample) / denominator).toShort()
            }
        }
        return later
    }
}
