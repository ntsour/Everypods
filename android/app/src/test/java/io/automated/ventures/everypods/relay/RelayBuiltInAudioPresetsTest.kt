package io.automated.ventures.everypods.relay

import org.junit.Assert.*
import org.junit.Test

class RelayBuiltInAudioPresetsTest {
    private val presets = RelayBuiltInAudioPresets.entries.associateBy { it.id }
    private fun tuning(id: String) = presets.getValue(id).tuning

    @Test
    fun everyPresetIsDistinctAndRoundTripsTheExistingWireFormat() {
        assertEquals(RelayBuiltInAudioPresets.entries.size, presets.size)
        assertEquals(presets.size, presets.values.map { it.tuning }.distinct().size)
        for (preset in presets.values) {
            assertEquals(preset.tuning, RelayAudioTuning.decodeWire(preset.tuning.encodeWire()))
        }
    }

    @Test
    fun individualTestsChangeOnlyTheirNamedSettingFromLowLatency() {
        val baseline = tuning("low_latency")
        assertEquals(baseline, tuning("test_jitter_60").copy(initialJitterMs = 80, maximumJitterMs = 160))
        assertEquals(baseline, tuning("test_jitter_40").copy(initialJitterMs = 80, maximumJitterMs = 160))
        assertEquals(baseline, tuning("test_playback_20").copy(playbackBufferMs = 40))
        assertEquals(baseline, tuning("test_capture_20").copy(captureBufferMs = 40))
        assertEquals(baseline, tuning("test_retransmission_0").copy(retransmissionMs = 20))
        assertEquals(baseline, tuning("test_combined").copy(initialJitterMs = 80, maximumJitterMs = 160,
            playbackBufferMs = 40, captureBufferMs = 40))
        assertEquals(RelayAudioTuning.DEFAULT, tuning("balanced"))
    }
}
