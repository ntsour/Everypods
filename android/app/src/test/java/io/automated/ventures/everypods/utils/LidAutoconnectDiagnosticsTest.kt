package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LidAutoconnectDiagnosticsTest {
    @Test fun `report streams persistent events that no longer fit in live preview and redacts identifiers`() {
        val context = RuntimeEnvironment.getApplication()
        LidAutoconnectDiagnostics.initialize(context)
        repeat(300) { index ->
            LidAutoconnectDiagnostics.logEvent("export_test", mapOf("index" to index,
                "peer" to "00:11:22:33:44:55", "address" to "192.168.1.10"))
        }
        assertFalse(LidAutoconnectDiagnostics.ringLinesOldestFirst().any { it.contains("index=0 ") })
        val report = LidAutoconnectDiagnostics.writeFailureReport(context).readText()
        assertTrue(report.contains("PERSISTENT CASE / CONNECTION HISTORY"))
        assertTrue(report.contains("index=0 "))
        assertTrue(report.contains("index=299 "))
        assertTrue(report.contains("journalWriteError=none"))
        assertFalse(report.contains("00:11:22:33:44:55"))
        assertFalse(report.contains("192.168.1.10"))
        assertTrue(report.contains("elapsedMs="))
    }
}
