package io.automated.ventures.everypods.bluetooth.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedDeviceConnectTest {

    private data class Device(val address: String)

    private val pods = Device("20:52:1D:89:33:C9")

    @Test
    fun `connects when the detected device is still current`() {
        val connected = mutableListOf<Device>()
        assertTrue(connectIfStillCurrent(pods, { pods }) { connected += it })
        assertEquals(listOf(pods), connected)
    }

    @Test
    fun `disconnect clearing the device before the coroutine runs skips the connect`() = runBlocking {
        // Same order as the 1.0.8 crash: CONNECTION_DETECTED sets the field and launches an IO
        // coroutine, then DISCONNECTED on the main thread nulls the field before it runs.
        val field = AtomicReference<Device?>(pods)
        val receiverReturned = CountDownLatch(1)
        val connected = mutableListOf<Device>()

        val detected = field.get()!!
        val job = async(Dispatchers.IO) {
            receiverReturned.await(5, TimeUnit.SECONDS)
            connectIfStillCurrent(detected, { field.get() }) { connected += it }
        }
        field.set(null) // AIRPODS_DISCONNECTED
        receiverReturned.countDown()

        assertFalse(job.await())
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a different device replacing the detected one skips the connect`() {
        val connected = mutableListOf<Device>()
        assertFalse(connectIfStillCurrent(pods, { Device("AA:BB:CC:DD:EE:FF") }) { connected += it })
        assertTrue(connected.isEmpty())
    }
}
