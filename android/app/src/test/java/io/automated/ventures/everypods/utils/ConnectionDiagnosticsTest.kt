package io.automated.ventures.everypods.utils

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectionDiagnosticsTest {
    @Test fun `connection lifecycle export admits safe fields and redacts identifiers`() {
        val mac = "01:23:45:67:89:AB"
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val address = "192.168.1.24"
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "retry_scheduled", mapOf(
            "state" to "Backoff", "retryMs" to 4_000, "address" to address,
            "reason" to "peer=$mac request=$uuid endpoint=$address",
        ))
        val line = LidAutoconnectDiagnostics.ringLinesOldestFirst().last()
        assertTrue(line.contains("state=Backoff"))
        assertTrue(line.contains("retryMs=4000"))
        assertTrue(line.contains("[redacted-mac]"))
        assertTrue(line.contains("[redacted-id]"))
        assertTrue(line, line.contains("[redacted-address]"))
        assertFalse(line.contains(mac))
        assertFalse(line.contains(uuid))
        assertFalse(line.contains(address))
    }
}
