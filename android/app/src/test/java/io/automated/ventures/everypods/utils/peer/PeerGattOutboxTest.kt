package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGattOutboxTest {
    @Test
    fun `hands out one chunk at a time and only after the previous was sent`() {
        val box = PeerGattOutbox()
        assertTrue(box.enqueue(ByteArray(45) { it.toByte() }))
        val first = box.next(0)!!
        assertEquals(20, first.size)
        assertNull("one notification in flight", box.next(1))
        box.onSent()
        assertEquals(20, box.next(2)!!.size)
        box.onSent()
        val last = box.next(3)!!
        assertEquals(5, last.size)
        assertEquals(44.toByte(), last.last())
        box.onSent()
        assertNull(box.next(4))
        assertTrue(box.isEmpty)
    }

    @Test
    fun `chunks follow the negotiated MTU`() {
        val box = PeerGattOutbox()
        box.mtu = 185
        box.enqueue(ByteArray(400))
        assertEquals(182, box.next(0)!!.size)
        box.mtu = 1000
        box.onSent()
        assertEquals(182, box.next(0)!!.size)  // already-chunked data keeps its size
        val fresh = PeerGattOutbox().apply { mtu = 1000 }
        fresh.enqueue(ByteArray(600))
        assertEquals(512, fresh.next(0)!!.size)  // MTU clamped to 517, chunk to the 512 attribute limit
    }

    @Test
    fun `a refused notify keeps the chunk for a retry`() {
        val box = PeerGattOutbox()
        box.enqueue(byteArrayOf(1, 2, 3))
        val chunk = box.next(0)!!
        box.onRefused()
        assertArrayEquals(chunk, box.next(1))
    }

    @Test
    fun `a lost onNotificationSent does not stall the queue forever`() {
        val box = PeerGattOutbox()
        box.enqueue(byteArrayOf(1))
        box.enqueue(byteArrayOf(2))
        box.next(1_000)
        assertFalse(box.expireInFlight(1_500, 1_000))
        assertTrue(box.expireInFlight(2_000, 1_000))
        assertArrayEquals(byteArrayOf(2), box.next(2_001))
    }

    @Test
    fun `refuses records beyond the queue limit`() {
        val box = PeerGattOutbox(maxQueuedBytes = 10)
        assertTrue(box.enqueue(ByteArray(8)))
        assertFalse(box.enqueue(ByteArray(3)))
        box.next(0); box.onSent()
        assertTrue(box.enqueue(ByteArray(10)))
        box.clear()
        assertTrue(box.isEmpty)
        assertFalse(box.inFlight)
    }

    @Test
    fun `chunks never exceed the 512 byte attribute limit even at MTU 517`() {
        val box = PeerGattOutbox()
        box.mtu = 517
        assertTrue(box.enqueue(ByteArray(1300)))
        assertEquals(512, box.next(0)!!.size)
        box.onSent()
        assertEquals(512, box.next(1)!!.size)
        box.onSent()
        assertEquals(276, box.next(2)!!.size)
    }
}
