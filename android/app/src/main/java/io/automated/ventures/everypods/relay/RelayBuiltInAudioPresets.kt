/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import androidx.annotation.StringRes
import io.automated.ventures.everypods.R

data class RelayBuiltInAudioPreset(
    val id: String,
    val tuning: RelayAudioTuning,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val nameArguments: List<Int> = emptyList(),
)

/** Tests start from the existing Low latency profile; selecting one never changes other peers. */
object RelayBuiltInAudioPresets {
    private val lowLatency = RelayAudioTuning(
        initialJitterMs = 80, maximumJitterMs = 160, adaptiveBufferMode = 2,
        playbackBufferMs = 40, captureBufferMs = 40, frameDurationMs = 10,
        lowDelayMode = true, bitrateKbps = 160, retransmissionMs = 20,
    )

    val entries = listOf(
        RelayBuiltInAudioPreset("balanced", RelayAudioTuning.DEFAULT,
            R.string.audio_relay_preset_balanced, R.string.audio_relay_preset_balanced_desc),
        RelayBuiltInAudioPreset("low_latency", lowLatency,
            R.string.audio_relay_preset_low_latency, R.string.audio_relay_preset_low_latency_desc),
        RelayBuiltInAudioPreset("unstable", RelayAudioTuning(
            initialJitterMs = 240, maximumJitterMs = 500, adaptiveBufferMode = 1,
            playbackBufferMs = 120, captureBufferMs = 80, bitrateKbps = 128,
        ), R.string.audio_relay_preset_unstable, R.string.audio_relay_preset_unstable_desc),
        RelayBuiltInAudioPreset("test_jitter_60", lowLatency.copy(initialJitterMs = 60, maximumJitterMs = 120),
            R.string.audio_relay_preset_test_jitter, R.string.audio_relay_preset_test_jitter_desc, listOf(60, 120)),
        RelayBuiltInAudioPreset("test_jitter_40", lowLatency.copy(initialJitterMs = 40, maximumJitterMs = 80),
            R.string.audio_relay_preset_test_jitter, R.string.audio_relay_preset_test_jitter_desc, listOf(40, 80)),
        RelayBuiltInAudioPreset("test_playback_20", lowLatency.copy(playbackBufferMs = 20),
            R.string.audio_relay_preset_test_playback, R.string.audio_relay_preset_test_playback_desc, listOf(20)),
        RelayBuiltInAudioPreset("test_capture_20", lowLatency.copy(captureBufferMs = 20),
            R.string.audio_relay_preset_test_capture, R.string.audio_relay_preset_test_capture_desc, listOf(20)),
        RelayBuiltInAudioPreset("test_retransmission_0", lowLatency.copy(retransmissionMs = 0),
            R.string.audio_relay_preset_test_retransmission, R.string.audio_relay_preset_test_retransmission_desc, listOf(0)),
        RelayBuiltInAudioPreset("test_combined", lowLatency.copy(
            initialJitterMs = 40, maximumJitterMs = 80, playbackBufferMs = 20, captureBufferMs = 20,
        ), R.string.audio_relay_preset_test_combined, R.string.audio_relay_preset_test_combined_desc),
    )
}
