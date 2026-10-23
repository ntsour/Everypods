package io.automated.ventures.everypods.utils.peer

import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PeerConnectionSupervisorTest {
    @Test fun `serialized connect gate permits only one blocking dial globally`() = runBlocking {
        val supervisor = PeerConnectionSupervisor(dispatcher = Dispatchers.Default)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            List(2) {
                async {
                    supervisor.connectSerialized {
                        val now = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, now) }
                        delay(30)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, peak.get())
    }

    @Test fun `owned peer loop is closed and joined before stop returns`() = runBlocking {
        val supervisor = PeerConnectionSupervisor(dispatcher = Dispatchers.Default)
        val socket = mockk<BluetoothSocket>(relaxed = true)
        val connection = PeerConnection("AA:BB:CC:DD:EE:FF", 1).also { it.socket = socket }
        val entered = CompletableDeferred<Unit>()
        supervisor.launch(connection) { entered.complete(Unit); awaitCancellation() }
        entered.await()
        supervisor.stopAndJoin(connection)
        assertFalse(connection.job?.isActive == true)
        verify(exactly = 1) { socket.close() }
    }

    @Test fun `socket factory and monotonic clock are injectable`() {
        val socket = mockk<BluetoothSocket>()
        val createdFor = AtomicReference<String>()
        val supervisor = PeerConnectionSupervisor(
            socketFactory = PeerSocketFactory { _, mac, _ -> createdFor.set(mac); socket },
            elapsedRealtime = { 9876L },
        )
        assertEquals(socket, supervisor.createSocket(mockk(), "AA:BB", UUID.randomUUID()))
        assertEquals("AA:BB", createdFor.get())
        assertEquals(9876L, supervisor.elapsedRealtime())
    }
}
