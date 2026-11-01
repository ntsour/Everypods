package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticControlSocketGateTest {
    @Test fun `only confirmed local audio permits an automatic control socket`() {
        assertTrue(AutomaticControlSocketGate.allows { true })
        assertFalse(AutomaticControlSocketGate.allows { false })
        // Bluetooth ON may deliver connection events before a profile proxy exists.
        assertFalse(AutomaticControlSocketGate.allows { null })
        assertFalse(AutomaticControlSocketGate.allows { throw SecurityException() })
    }
}
