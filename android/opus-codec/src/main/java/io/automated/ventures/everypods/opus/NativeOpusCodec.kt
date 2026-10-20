/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.opus


/** 48 kHz stereo Opus with the relay plan's 20 ms, 192 kbit/s constrained-VBR settings. */
class NativeOpusCodec(
    val frameDurationMs: Int = 20,
    bitrateKbps: Int = 192,
    lowDelayMode: Boolean = false,
) : AutoCloseable {
    private val samplesPerChannel = SAMPLE_RATE * frameDurationMs / 1_000
    private var encoderHandle = nativeCreateEncoder(frameDurationMs, bitrateKbps * 1_000, lowDelayMode)
    private var decoderHandle = nativeCreateDecoder()

    @Synchronized
    fun encode(pcm: ShortArray): ByteArray {
        check(encoderHandle != 0L) { "Encoder is closed" }
        require(pcm.size == samplesPerChannel * CHANNELS) { "Unexpected PCM frame size" }
        return checkNotNull(nativeEncode(encoderHandle, pcm, samplesPerChannel))
    }

    /** Pass null for one missing frame to ask libopus for packet-loss concealment. */
    @Synchronized
    fun decode(packet: ByteArray?): ShortArray {
        check(decoderHandle != 0L) { "Decoder is closed" }
        require(packet == null || packet.size <= MAX_PACKET_BYTES) { "Opus packet exceeds bound" }
        return checkNotNull(nativeDecode(decoderHandle, packet, samplesPerChannel))
    }

    /** A resumed live stream must not reuse prediction history from before suspension. */
    @Synchronized
    fun resetDecoder() {
        check(decoderHandle != 0L) { "Decoder is closed" }
        val replacement = nativeCreateDecoder()
        check(replacement != 0L) { "Decoder could not restart" }
        nativeDestroyDecoder(decoderHandle)
        decoderHandle = replacement
    }

    @Synchronized
    override fun close() {
        if (encoderHandle != 0L) nativeDestroyEncoder(encoderHandle)
        if (decoderHandle != 0L) nativeDestroyDecoder(decoderHandle)
        encoderHandle = 0L
        decoderHandle = 0L
    }

    private external fun nativeCreateEncoder(frameDurationMs: Int, bitrate: Int, lowDelay: Boolean): Long
    private external fun nativeCreateDecoder(): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, samplesPerChannel: Int): ByteArray?
    private external fun nativeDecode(handle: Long, packet: ByteArray?, samplesPerChannel: Int): ShortArray?
    private external fun nativeDestroyEncoder(handle: Long)
    private external fun nativeDestroyDecoder(handle: Long)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val SAMPLES_PER_CHANNEL = 960
        const val MAX_PACKET_BYTES = 4_096

        init { System.loadLibrary("everypods_opus_jni") }
    }
}
