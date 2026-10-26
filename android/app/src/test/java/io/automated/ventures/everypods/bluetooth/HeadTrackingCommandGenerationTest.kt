package io.automated.ventures.everypods.bluetooth

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HeadTrackingCommandGenerationTest {
    @After fun cleanUp() = BluetoothConnectionManager.clearCurrentConnection()
    private val device = mockk<BluetoothDevice> { every { address } returns "00:11:22:33:44:55" }
    private fun socket(output: ByteArrayOutputStream) = mockk<BluetoothSocket>(relaxed = true) {
        every { isConnected } returns true
        every { outputStream } returns output
    }
    @Test fun `matching generation sends unchanged protocol bytes`() {
        val output = ByteArrayOutputStream()
        val generation = BluetoothConnectionManager.setCurrentConnection(socket(output), device)
        val manager = AACPManager()
        val payload = manager.createAlternateStartHeadTrackingPacket()
        assertTrue(manager.sendDataPacketForGeneration(payload, generation))
        assertArrayEquals(manager.createDataPacket(payload), output.toByteArray())
    }
    @Test fun `stale command cannot write to replacement socket`() {
        val old = BluetoothConnectionManager.setCurrentConnection(socket(ByteArrayOutputStream()), device)
        val output = ByteArrayOutputStream()
        val replacement = socket(output)
        BluetoothConnectionManager.setCurrentConnection(replacement, device)
        val manager = AACPManager()
        assertFalse(manager.sendDataPacketForGeneration(manager.createAlternateStopHeadTrackingPacket(), old))
        assertEquals(0, output.size())
        verify(exactly = 0) { replacement.outputStream }
    }
    @Test fun `reconnect during stream lookup never retargets the captured command`() {
        val oldOutput = ByteArrayOutputStream()
        val newOutput = ByteArrayOutputStream()
        val replacement = socket(newOutput)
        val original = socket(oldOutput)
        val generation = BluetoothConnectionManager.setCurrentConnection(original, device)
        every { original.outputStream } answers {
            BluetoothConnectionManager.setCurrentConnection(replacement, device)
            oldOutput
        }
        val manager = AACPManager()
        assertTrue(manager.sendDataPacketForGeneration(manager.createAlternateStopHeadTrackingPacket(), generation))
        assertTrue(oldOutput.size() > 0)
        assertEquals(0, newOutput.size())
    }
    @Test fun `write failure returns failure without closing the shared connection`() {
        val target = socket(ByteArrayOutputStream())
        val generation = BluetoothConnectionManager.setCurrentConnection(target, device)
        every { target.outputStream } throws IOException("test")
        val manager = AACPManager()
        assertFalse(manager.sendDataPacketForGeneration(manager.createAlternateStartHeadTrackingPacket(), generation))
        verify(exactly = 0) { target.close() }
    }
}
