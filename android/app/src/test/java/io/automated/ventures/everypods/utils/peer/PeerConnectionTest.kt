package io.automated.ventures.everypods.utils.peer

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.channels.Channel
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionTest {
    @Test fun `stop closes both protocol sockets and queues exactly once`() {
        val legacy = mockk<BluetoothSocket>(relaxed = true)
        val v2 = mockk<BluetoothSocket>(relaxed = true)
        val legacyQueue = Channel<ByteArray>(1)
        val v2Queue = Channel<ByteArray>(1)
        val link = PeerConnection("AA:BB:CC:DD:EE:FF", 9).also {
            it.socket = legacy
            it.v2Socket = v2
            it.outboundQueue = legacyQueue
            it.v2Queue = v2Queue
            it.isConnected = true
            it.legacyReady = true
        }

        link.stop()
        link.stop()

        verify(exactly = 1) { legacy.close() }
        verify(exactly = 1) { v2.close() }
        assertFalse(link.isConnected)
        assertFalse(link.legacyReady)
        assertFalse(link.bluetoothWake.trySend(Unit).isSuccess)
        assertFalse(legacyQueue.trySend(byteArrayOf(1)).isSuccess)
        assertFalse(v2Queue.trySend(byteArrayOf(2)).isSuccess)
        assertTrue(link.socket == null)
        assertTrue(link.v2Socket == null)
    }
}
