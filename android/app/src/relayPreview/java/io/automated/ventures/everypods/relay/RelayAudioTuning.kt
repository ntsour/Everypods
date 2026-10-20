/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.SharedPreferences
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Experimental per-peer profile sent by the source and echoed by the receiver before media starts. */
data class RelayAudioTuning(
    val initialJitterMs: Int = 200,
    val maximumJitterMs: Int = 500,
    val adaptiveBufferMode: Int = 0,
    val playbackBufferMs: Int = 0,
    val captureBufferMs: Int = 0,
    val frameDurationMs: Int = 20,
    val lowDelayMode: Boolean = false,
    val bitrateKbps: Int = 192,
    val retransmissionMs: Int = 80,
) {
    fun validated(): RelayAudioTuning {
        require(initialJitterMs in JITTER_OPTIONS)
        require(maximumJitterMs in JITTER_OPTIONS && maximumJitterMs >= initialJitterMs)
        require(adaptiveBufferMode in 0..2)
        require(playbackBufferMs in PLAYBACK_BUFFER_OPTIONS)
        require(captureBufferMs in CAPTURE_BUFFER_OPTIONS)
        require(frameDurationMs in setOf(10, 20))
        require(bitrateKbps in BITRATE_OPTIONS)
        require(retransmissionMs in RETRANSMISSION_OPTIONS)
        return this
    }

    fun encodeWire(): ByteArray = validated().let { value ->
        ByteBuffer.allocate(WIRE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(value.frameDurationMs.toByte())
            .putShort(value.bitrateKbps.toShort())
            .put(if (value.lowDelayMode) 1 else 0)
            .putShort(value.initialJitterMs.toShort())
            .putShort(value.maximumJitterMs.toShort())
            .put(value.adaptiveBufferMode.toByte())
            .put(bufferCode(value.playbackBufferMs).toByte())
            .put(bufferCode(value.captureBufferMs).toByte())
            .put(retransmissionCode(value.retransmissionMs).toByte())
            .array()
    }

    companion object {
        const val WIRE_BYTES = 12
        val JITTER_OPTIONS = listOf(60, 80, 100, 120, 160, 200, 240, 300, 400, 500)
        val PLAYBACK_BUFFER_OPTIONS = listOf(0, 40, 60, 80, 120, 160, 200)
        val CAPTURE_BUFFER_OPTIONS = listOf(0, 40, 60, 80, 120, 160)
        val BITRATE_OPTIONS = listOf(96, 128, 160, 192, 256)
        val RETRANSMISSION_OPTIONS = listOf(0, 20, 40, 80)
        val DEFAULT = RelayAudioTuning()

        fun decodeWire(bytes: ByteArray): RelayAudioTuning {
            require(bytes.size == WIRE_BYTES)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val frame = input.get().toInt() and 0xff
            val bitrate = input.short.toInt() and 0xffff
            val lowDelay = input.get().toInt().also { require(it in 0..1) } == 1
            val initial = input.short.toInt() and 0xffff
            val maximum = input.short.toInt() and 0xffff
            val adaptive = input.get().toInt() and 0xff
            val playback = bufferValue(input.get().toInt() and 0xff, PLAYBACK_BUFFER_OPTIONS)
            val capture = bufferValue(input.get().toInt() and 0xff, CAPTURE_BUFFER_OPTIONS)
            val retransmission = retransmissionValue(input.get().toInt() and 0xff)
            return RelayAudioTuning(initial, maximum, adaptive, playback, capture, frame, lowDelay, bitrate, retransmission).validated()
        }

        fun load(preferences: SharedPreferences, peerMac: String): RelayAudioTuning {
            val json = preferences.getString(storageKey(peerMac), null) ?: return DEFAULT
            return runCatching {
                val value = JSONObject(json)
                RelayAudioTuning(
                    initialJitterMs = value.optInt("initial", DEFAULT.initialJitterMs),
                    maximumJitterMs = value.optInt("maximum", DEFAULT.maximumJitterMs),
                    adaptiveBufferMode = value.optInt("adaptive", DEFAULT.adaptiveBufferMode),
                    playbackBufferMs = value.optInt("playback", DEFAULT.playbackBufferMs),
                    captureBufferMs = value.optInt("capture", DEFAULT.captureBufferMs),
                    frameDurationMs = value.optInt("frame", DEFAULT.frameDurationMs),
                    lowDelayMode = value.optBoolean("lowDelay", DEFAULT.lowDelayMode),
                    bitrateKbps = value.optInt("bitrate", DEFAULT.bitrateKbps),
                    retransmissionMs = value.optInt("retransmission", DEFAULT.retransmissionMs),
                ).validated()
            }.getOrDefault(DEFAULT)
        }

        fun save(preferences: SharedPreferences, peerMac: String, tuning: RelayAudioTuning) {
            tuning.validated()
            val json = JSONObject().put("initial", tuning.initialJitterMs).put("maximum", tuning.maximumJitterMs)
                .put("adaptive", tuning.adaptiveBufferMode).put("playback", tuning.playbackBufferMs)
                .put("capture", tuning.captureBufferMs).put("frame", tuning.frameDurationMs)
                .put("lowDelay", tuning.lowDelayMode).put("bitrate", tuning.bitrateKbps)
                .put("retransmission", tuning.retransmissionMs)
            preferences.edit().putString(storageKey(peerMac), json.toString()).apply()
        }

        fun clear(preferences: SharedPreferences, peerMac: String) { preferences.edit().remove(storageKey(peerMac)).apply() }
        private fun storageKey(peerMac: String) = "audio_relay_tuning_${peerMac.uppercase(Locale.ROOT)}"
        private fun bufferCode(value: Int) = if (value == 0) 0 else PLAYBACK_BUFFER_OPTIONS.indexOf(value)
        private fun bufferValue(code: Int, options: List<Int>): Int { require(code in options.indices); return options[code] }
        private fun retransmissionCode(value: Int) = RETRANSMISSION_OPTIONS.indexOf(value)
        private fun retransmissionValue(code: Int): Int { require(code in RETRANSMISSION_OPTIONS.indices); return RETRANSMISSION_OPTIONS[code] }
    }
}
