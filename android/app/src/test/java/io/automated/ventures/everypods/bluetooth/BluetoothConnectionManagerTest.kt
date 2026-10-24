package io.automated.ventures.everypods.bluetooth

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BluetoothConnectionManagerTest {
    @After fun cleanUp() = BluetoothConnectionManager.clearCurrentConnection()

    @Test fun `delayed disconnect generation cannot clear replacement socket`() {
        val firstSocket = mockk<BluetoothSocket>(relaxed = true)
        val secondSocket = mockk<BluetoothSocket>(relaxed = true)
        val device = mockk<BluetoothDevice> { every { address } returns "00:11:22:33:44:55" }

        val oldGeneration = BluetoothConnectionManager.setCurrentConnection(firstSocket, device)
        val replacementGeneration = BluetoothConnectionManager.setCurrentConnection(secondSocket, device)

        assertTrue(replacementGeneration > oldGeneration)
        assertFalse(BluetoothConnectionManager.clearCurrentConnectionIfGeneration(oldGeneration))
        assertSame(secondSocket, BluetoothConnectionManager.getCurrentSocket())
        verify(exactly = 0) { secondSocket.close() }
    }

    @Test fun `disconnect broadcast remains valid after its own socket was already cleared`() {
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val device = mockk<BluetoothDevice> { every { address } returns "00:11:22:33:44:55" }
        val generation = BluetoothConnectionManager.setCurrentConnection(socket, device)

        assertTrue(BluetoothConnectionManager.clearCurrentConnectionIfGeneration(generation))
        assertNull(BluetoothConnectionManager.getCurrentSocket())
        assertTrue(BluetoothConnectionManager.isCurrentConnectionGeneration(generation))

        val laterSocket = mockk<BluetoothSocket>(relaxed = true)
        BluetoothConnectionManager.setCurrentConnection(laterSocket, device)
        assertFalse(BluetoothConnectionManager.isCurrentConnectionGeneration(generation))
        assertSame(laterSocket, BluetoothConnectionManager.getCurrentSocket())
    }
}
