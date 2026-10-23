package io.automated.ventures.everypods.bluetooth.hrm.dircon

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HeartRateBroadcasterTest {
    private class BlockingWriteSocket : Socket() {
        private val closed = AtomicBoolean(false)
        val writeEntered = CountDownLatch(1)
        val closedSignal = CountDownLatch(1)
        private val input = PipedInputStream(8192)
        private val feeder = PipedOutputStream(input)
        private val releaseWrite = CountDownLatch(1)
        private val output = object : OutputStream() {
            override fun write(value: Int) {
                writeEntered.countDown()
                releaseWrite.await()
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                releaseWrite.await()
            }
            override fun flush() = Unit
        }

        fun feed(bytes: ByteArray) = feeder.write(bytes)
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun setSoTimeout(timeout: Int) = Unit
        override fun isClosed(): Boolean = closed.get()
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                releaseWrite.countDown()
                runCatching { input.close() }
                runCatching { feeder.close() }
                closedSignal.countDown()
            }
        }
    }

    private fun bridge(elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime): HeartRateBroadcaster {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(android.net.ConnectivityManager::class.java) } returns mockk(relaxed = true)
        every { context.getSystemService(android.net.nsd.NsdManager::class.java) } returns mockk(relaxed = true)
        return HeartRateBroadcaster(context, {}, { fail("Unexpected transport failure") }, elapsedRealtime)
    }

    private fun connect(bridge: HeartRateBroadcaster): Socket {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            client.soTimeout = 1000
            bridge.acceptClient(listener.accept())
            return client
        }
    }

    private fun receive(socket: Socket): ByteArray {
        val input = DataInputStream(socket.getInputStream())
        val header = ByteArray(6).also { input.readFully(it) }
        val length = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
        return header + ByteArray(length).also { input.readFully(it) }
    }

    @Test fun `TCP discovery subscription fresh sample unsubscribe and stop`() {
        val bridge = bridge()
        try {
            connect(bridge).use { socket ->
                val out = socket.getOutputStream()
                // A publication before subscription must never be replayed.
                bridge.publish(82)
                out.write(byteArrayOf(1, 1, 42, 0, 0, 0))
                val discovery = receive(socket)
                assertEquals(42, discovery[2].toInt())
                assertArrayEquals(DirconProtocol.uuid(0x180D), discovery.copyOfRange(6, 22))
                val characteristic = DirconProtocol.uuid(0x2A37)
                out.write(DirconProtocol.Frame(5, 43, payload = characteristic + byteArrayOf(1)).encode())
                val subscribeDeadline = System.nanoTime() + 1_000_000_000L
                while (bridge.snapshot().subscriptions == 0 && System.nanoTime() < subscribeDeadline) Thread.sleep(5)
                assertEquals(1, bridge.snapshot().subscriptions)
                // Race a fresh publication with subscribe. The ACK must remain first.
                bridge.publish(201)
                assertEquals(5, receive(socket)[1].toInt())
                val firstSample = receive(socket)
                assertEquals(6, firstSample[1].toInt())
                assertEquals(201, firstSample.last().toInt() and 255)
                socket.soTimeout = 1000
                bridge.publish(200)
                val sample = receive(socket)
                assertEquals(6, sample[1].toInt())
                assertEquals(200, sample.last().toInt() and 255)
                out.write(DirconProtocol.Frame(5, 44, payload = characteristic + byteArrayOf(0)).encode())
                assertEquals(5, receive(socket)[1].toInt())
                bridge.publish(99)
                socket.soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { receive(socket) }
                bridge.stop()
                assertEquals(-1, socket.getInputStream().read())
                assertEquals(0, bridge.snapshot().clients)
            }
        } finally { bridge.stop() }
    }

    @Test fun `sensor gap disconnects receiver and reconnect does not replay old sample`() {
        val bridge = bridge()
        try {
            connect(bridge).use { socket ->
                bridge.clearReading()
                assertEquals(-1, socket.getInputStream().read())
            }
            connect(bridge).use { socket ->
                socket.getOutputStream().write(DirconProtocol.Frame(5, 1,
                    payload = DirconProtocol.uuid(0x2A37) + byteArrayOf(1)).encode())
                receive(socket)
                socket.soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { receive(socket) }
            }
        } finally { bridge.stop() }
    }

    @Test fun `malformed peer closes without stopping other clients`() {
        val bridge = bridge()
        try {
            connect(bridge).use { bad ->
                connect(bridge).use { good ->
                    bad.getOutputStream().write(byteArrayOf(2, 1, 0, 0, 0, 0))
                    assertEquals(-1, bad.getInputStream().read())
                    good.getOutputStream().write(byteArrayOf(1, 7, 9, 0, 0, 0))
                    assertArrayEquals(byteArrayOf(1, 7, 9, 0, 0, 0), receive(good))
                }
            }
        } finally { bridge.stop() }
    }

    @Test fun `client limit rejects fifth receiver without disturbing four active receivers`() {
        val bridge = bridge()
        val sockets = mutableListOf<Socket>()
        try {
            repeat(5) { sockets += connect(bridge) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bridge.snapshot().clients != 4 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(4, bridge.snapshot().clients)
            sockets.take(4).forEachIndexed { index, socket ->
                socket.getOutputStream().write(DirconProtocol.Frame(1, index).encode())
                assertEquals(index, receive(socket)[2].toInt())
            }
            sockets[4].soTimeout = 1_000
            assertEquals(-1, sockets[4].getInputStream().read())
        } finally {
            sockets.forEach { runCatching { it.close() } }
            bridge.stop()
        }
    }

    @Test fun `client registry returns to zero across one hundred sessions`() {
        val bridge = bridge()
        try {
            repeat(100) { index ->
                connect(bridge).use { socket ->
                    socket.getOutputStream().write(DirconProtocol.Frame(1, index and 0xff).encode())
                    assertEquals(index and 0xff, receive(socket)[2].toInt())
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (bridge.snapshot().clients != 0 && System.nanoTime() < deadline) Thread.sleep(2)
                assertEquals("client registry leaked after cycle $index", 0, bridge.snapshot().clients)
            }
        } finally { bridge.stop() }
    }

    @Test fun `partial frame deadline is measured from first byte despite drip feed`() {
        val elapsed = AtomicLong(10_000L)
        val bridge = bridge { elapsed.get() }
        try {
            connect(bridge).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write(byteArrayOf(1, 1))
                // Let the I/O dispatcher consume the first fragment at t=10s.
                Thread.sleep(150)
                elapsed.set(13_000L)
                socket.getOutputStream().write(byteArrayOf(42, 0))
                Thread.sleep(150)
                // If each fragment restarted the timer, this client would remain open.
                elapsed.set(15_001L)
                Thread.sleep(1_200)
                val closed = try { socket.getInputStream().read() == -1 } catch (_: java.net.SocketException) { true }
                assertTrue("partial frame deadline did not close the client", closed)
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (bridge.snapshot().clients != 0 && System.nanoTime() < cleanupDeadline) Thread.sleep(5)
                assertEquals(0, bridge.snapshot().clients)
                assertEquals(0L, bridge.snapshot().requests)
            }
        } finally { bridge.stop() }
    }

    @Test fun `bounded response queue closes only the overflowing client`() {
        val bridge = bridge()
        val blocked = BlockingWriteSocket()
        try {
            connect(bridge).use { healthy ->
                bridge.acceptClient(blocked)
                val requests = (1..32).flatMap { id ->
                    DirconProtocol.Frame(1, id and 0xff).encode().asIterable()
                }.toByteArray()
                blocked.feed(requests)
                val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (bridge.snapshot().requests == 0L && System.nanoTime() < requestDeadline) Thread.sleep(5)
                assertTrue("stalled client requests were not parsed", bridge.snapshot().requests > 0L)
                assertTrue(blocked.writeEntered.await(3, TimeUnit.SECONDS))
                assertTrue("overflow did not close stalled receiver", blocked.closedSignal.await(3, TimeUnit.SECONDS))
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (bridge.snapshot().clients != 1 && System.nanoTime() < cleanupDeadline) Thread.sleep(5)
                assertEquals(1, bridge.snapshot().clients)
                healthy.getOutputStream().write(DirconProtocol.Frame(1, 99).encode())
                assertEquals(99, receive(healthy)[2].toInt())
            }
        } finally { bridge.stop() }
    }

    @Test fun `blocked socket write is closed by independent write deadline`() {
        val bridge = bridge()
        val blocked = BlockingWriteSocket()
        try {
            bridge.acceptClient(blocked)
            blocked.feed(DirconProtocol.Frame(1, 1).encode())
            val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (bridge.snapshot().requests == 0L && System.nanoTime() < requestDeadline) Thread.sleep(5)
            assertEquals("request not parsed", 1L, bridge.snapshot().requests)
            assertTrue(blocked.writeEntered.await(3, TimeUnit.SECONDS))
            assertTrue("write watchdog did not close socket", blocked.closedSignal.await(7, TimeUnit.SECONDS))
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bridge.snapshot().clients != 0 && System.nanoTime() < cleanupDeadline) Thread.sleep(5)
            assertEquals(0, bridge.snapshot().clients)
        } finally { bridge.stop() }
    }

}
