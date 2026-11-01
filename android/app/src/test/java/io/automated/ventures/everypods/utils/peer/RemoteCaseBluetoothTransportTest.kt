package io.automated.ventures.everypods.utils.peer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteCaseBluetoothTransportTest {
    @Test fun `stalled exchange times out and releases its wake lease`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true)
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val address = "00:11:22:33:44:55"
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releases = AtomicInteger()
        every { adapter.getRemoteDevice(address) } returns device
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        every { device.createRfcommSocketToServiceRecord(any()) } returns socket
        every { socket.connect() } answers { entered.countDown(); closed.await(2, TimeUnit.SECONDS); throw IOException("closed") }
        every { socket.close() } answers { closed.countDown() }
        val transport = RemoteCaseBluetoothTransport(adapter, Hooks(address), acquireWake = {
            RemoteCaseBluetoothTransport.WakeLease { releases.incrementAndGet(); released.countDown() }
        }, timeoutMs = 100)
        try {
            transport.publish(RemoteCaseEvidenceExchange.Observation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, true, true))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(released.await(2, TimeUnit.SECONDS))
            org.junit.Assert.assertEquals(1, releases.get())
        } finally { transport.close() }
    }

    private class Hooks(private val address: String) : RemoteCaseBluetoothTransport.Hooks {
        val session = UUID.randomUUID()
        override fun peers() = setOf(address)
        override fun peerSession(address: String): UUID? = if (address == this.address) session else null
        override fun currentObservation(id: UUID): RemoteCaseEvidenceExchange.Observation? = null
        override fun receiverState(address: String): RemoteCaseEvidenceExchange.ReceiverState? = null
        override fun onAccepted(evidence: RemoteCaseEvidenceExchange.Accepted) = Unit
        override fun onWithdrawal(address: String, message: RemoteCaseEvidenceMessage.Message.Withdrawal) = Unit
    }

    @Test fun `unbonded peer is never dialed`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true)
        val address = "00:11:22:33:44:55"
        every { adapter.getRemoteDevice(address) } returns device
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        val transport = RemoteCaseBluetoothTransport(adapter, Hooks(address))
        try {
            transport.publish(RemoteCaseEvidenceExchange.Observation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, true, true))
            verify(timeout = 2_000) { device.bondState }
            verify(exactly = 0) { device.createRfcommSocketToServiceRecord(any()) }
        } finally { transport.close() }
    }

    @Test fun `closing transport closes secure listener and unblocks accept`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val server = mockk<BluetoothServerSocket>(relaxed = true)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        every { adapter.listenUsingRfcommWithServiceRecord(any(), RemoteCaseBluetoothTransport.SERVICE_UUID) } returns server
        every { server.accept() } answers { entered.countDown(); released.await(2, TimeUnit.SECONDS); throw IOException("closed") }
        every { server.close() } answers { released.countDown() }
        val transport = RemoteCaseBluetoothTransport(adapter, Hooks("00:11:22:33:44:55"))
        transport.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        transport.close()
        verify(timeout = 2_000, atLeast = 1) { server.close() }
    }

    @Test fun `peer without extension closes only the attempted socket`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true)
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val address = "00:11:22:33:44:55"
        every { adapter.getRemoteDevice(address) } returns device
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        every { device.createRfcommSocketToServiceRecord(RemoteCaseBluetoothTransport.SERVICE_UUID) } returns socket
        every { socket.connect() } throws IOException("service absent")
        val transport = RemoteCaseBluetoothTransport(adapter, Hooks(address))
        try {
            transport.publish(RemoteCaseEvidenceExchange.Observation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, true, true))
            verify(timeout = 2_000) { socket.connect() }
            verify(timeout = 2_000) { socket.close() }
            verify(exactly = 0) { device.createInsecureRfcommSocketToServiceRecord(any()) }
        } finally { transport.close() }
    }

    @Test fun `bonded synchronized peer withdrawal creates a tombstone`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val server = mockk<BluetoothServerSocket>(relaxed = true)
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val device = mockk<BluetoothDevice>(relaxed = true)
        val hooks = mockk<RemoteCaseBluetoothTransport.Hooks>(relaxed = true)
        val address = "00:11:22:33:44:55"
        val session = UUID.randomUUID()
        val headset = UUID.randomUUID()
        val event = UUID.randomUUID()
        val message = RemoteCaseEvidenceMessage.Message.Withdrawal(session, headset, event)
        val state = RemoteCaseEvidenceExchange.ReceiverState(UUID.randomUUID(), session, headset, 1, true, true, true)
        every { adapter.listenUsingRfcommWithServiceRecord(any(), RemoteCaseBluetoothTransport.SERVICE_UUID) } returns server
        every { server.accept() } returnsMany listOf(socket) andThenThrows IOException("closed")
        every { socket.remoteDevice } returns device
        every { socket.inputStream } returns ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(message))
        every { device.address } returns address
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        every { hooks.peers() } returns setOf(address)
        every { hooks.peerSession(address) } returns session
        every { hooks.receiverState(address) } returns state
        val transport = RemoteCaseBluetoothTransport(adapter, hooks)
        try {
            transport.start()
            verify(timeout = 2_000) { hooks.onWithdrawal(address, message) }
            assertTrue(transport.isWithdrawn(address, session, event, android.os.SystemClock.elapsedRealtime()))
        } finally { transport.close() }
    }
}
