package io.automated.ventures.everypods.bluetooth.hrm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WahooDiagnosticBridgeTest {
    @Test fun `stop targets Wahoo with zero and does not unpair TICKR`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val bridge = WahooDiagnosticBridge(application)
        assertTrue(bridge.publish(201))
        bridge.stop()
        val broadcasts = shadowOf(application).broadcastIntents
        assertEquals(3, broadcasts.size)
        assertTrue(broadcasts.all { it.`package` == "com.wahoofitness.fitness" })
        assertEquals(201, broadcasts.first().getIntExtra("value", -1))
        assertTrue(broadcasts[1].action!!.endsWith("PAIR_SIM"))
        assertTrue(broadcasts.last().action!!.endsWith("SET_SIM_VALUE"))
        assertEquals("HR_CRUX", broadcasts.last().getStringExtra("cp"))
        assertEquals(0, broadcasts.last().getIntExtra("value", -1))
        assertFalse(broadcasts.any { it.action!!.contains("UNPAIR") })
    }

    @Test fun `reconnect clears Wahoo then pairs only after a new reading`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val bridge = WahooDiagnosticBridge(application)
        assertTrue(bridge.publish(70))
        assertTrue(bridge.reconnect())
        val before = shadowOf(application).broadcastIntents
        assertEquals(3, before.size)
        assertEquals(0, before.last().getIntExtra("value", -1))
        assertTrue(bridge.publish(71))
        val after = shadowOf(application).broadcastIntents
        assertEquals(5, after.size)
        assertEquals(71, after[3].getIntExtra("value", -1))
        assertTrue(after[4].action!!.endsWith("PAIR_SIM"))
    }
}
