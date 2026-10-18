package io.automated.ventures.everypods.relay

import io.automated.ventures.everypods.relay.OutputMatcher.Candidate
import io.automated.ventures.everypods.relay.OutputMatcher.Kind
import io.automated.ventures.everypods.relay.OutputMatcher.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputMatcherTest {
    private val speaker = Candidate(1, "", "Pixel speaker", Kind.SPEAKER)
    private val wired = Candidate(2, "", "USB-C", Kind.WIRED)
    private val airpods = Candidate(3, "AA:BB:CC:00:11:22", "Nikos's AirPods Pro", Kind.BLUETOOTH_A2DP)
    private val car = Candidate(4, "11:22:33:44:55:66", "VW Car", Kind.BLUETOOTH_A2DP)
    private val beats = Candidate(5, "99:88:77:66:55:44", "Powerbeats Pro", Kind.BLUETOOTH_A2DP)

    private fun match(r: Result) = (r as Result.Match).candidate

    @Test fun picksOnlyAirpodsAmongOutputs() {
        assertEquals(airpods, match(OutputMatcher.choose(listOf(speaker, car, airpods, wired), null, null)))
    }

    @Test fun neverFallsBackToSpeakerOrWired() {
        assertTrue(OutputMatcher.choose(listOf(speaker, wired), null, null) is Result.Refused)
        assertTrue(OutputMatcher.choose(listOf(speaker), null, "AA:BB:CC:00:11:22") is Result.Refused)
    }

    @Test fun nonAppleBluetoothNeedsExplicitPick() {
        assertTrue(OutputMatcher.choose(listOf(speaker, car), null, null) is Result.Refused)
        assertEquals(car, match(OutputMatcher.choose(listOf(speaker, car), null, "11:22:33:44:55:66")))
    }

    @Test fun pickedAndManagedAddressWinCaseInsensitively() {
        assertEquals(airpods, match(OutputMatcher.choose(listOf(beats, airpods), null, "aa:bb:cc:00:11:22")))
        assertEquals(beats, match(OutputMatcher.choose(listOf(beats, airpods), "99:88:77:66:55:44", "aa:bb:cc:00:11:22")))
    }

    @Test fun managedAddressMissingIsRefusedNotSubstituted() {
        assertTrue(OutputMatcher.choose(listOf(car, airpods), "00:00:00:00:00:01", null) is Result.Refused)
    }

    @Test fun ambiguousAppleRefused() {
        assertTrue(OutputMatcher.choose(listOf(beats, airpods), null, null) is Result.Refused)
    }

    @Test fun duplicateA2dpAndLeEntriesCollapse() {
        val le = airpods.copy(id = 9, kind = Kind.BLE_AUDIO)
        assertEquals(airpods, match(OutputMatcher.choose(listOf(airpods, le), null, null)))
    }

    @Test fun routeCheck() {
        assertTrue(OutputMatcher.routeOk(airpods.copy(id = 77), airpods))
        assertFalse(OutputMatcher.routeOk(speaker, airpods))
        assertFalse(OutputMatcher.routeOk(car, airpods))
        assertFalse(OutputMatcher.routeOk(null, airpods))
    }
}
