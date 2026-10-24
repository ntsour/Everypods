package io.automated.ventures.everypods.bluetooth.hrm

import org.junit.Assert.*
import org.junit.Test

class HeartRateContinuityTest {
    private var now = 100L
    private val sensor = HeartRateContinuity { now }

    @Test fun `freshness is monotonic and expires at five seconds`() {
        assertTrue(HeartRateContinuity.fresh(100, 5_099))
        assertFalse(HeartRateContinuity.fresh(100, 5_100))
        assertFalse(HeartRateContinuity.fresh(200, 100))
    }

    @Test fun `successful commands do not renew original deadline`() {
        sensor.beginOutage()
        assertEquals(60_100L, sensor.deadline)
        assertEquals(2_000L, sensor.nextDelay())
        now += 2_000
        sensor.commandAccepted()
        assertTrue(sensor.waitingForSample())
        now += 15_000
        assertFalse(sensor.waitingForSample())
        sensor.beginOutage()
        assertEquals(60_100L, sensor.deadline)
        assertEquals(4_000L, sensor.nextDelay())
        assertEquals(8_000L, sensor.nextDelay())
        now = 60_100
        assertTrue(sensor.expired())
        sensor.sampleAccepted()
        assertFalse(sensor.recovering)
        sensor.beginOutage()
        assertEquals(120_100L, sensor.deadline)
    }

    @Test fun `BLE consecutive failures reset only after stable advertising`() {
        val transport = HeartRateTransportContinuity { now }
        assertEquals(2_000L, transport.nextFailureDelay())
        now += 2_000
        transport.ready()
        now += 29_999
        transport.stable()
        assertFalse(transport.recovering)
        now++
        transport.stable()
        assertFalse(transport.recovering)
        assertEquals(2_000L, transport.nextFailureDelay())
        assertEquals(4_000L, transport.nextFailureDelay())
        assertEquals(8_000L, transport.nextFailureDelay())
        assertFalse(transport.exhausted())
        transport.nextFailureDelay()
        assertTrue(transport.exhausted())
    }

    @Test fun `monitor grace rejects changed device and expires at sixty seconds`() {
        val monitor = HeartRateMonitorWindow { now }
        monitor.start("airpods-a")
        assertTrue(monitor.suspend())
        assertFalse(monitor.suspend())
        assertFalse(monitor.canResume("airpods-b"))
        now += 59_999
        assertTrue(monitor.canResume("airpods-a"))
        now++
        assertTrue(monitor.expired())
        assertFalse(monitor.canResume("airpods-a"))
        monitor.clear()
        assertFalse(monitor.requested)
    }
}
