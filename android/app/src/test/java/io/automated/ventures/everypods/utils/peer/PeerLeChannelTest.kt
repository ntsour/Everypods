package io.automated.ventures.everypods.utils.peer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerLeChannelTest {
    private class FakeTransport : PeerLeChannel.Transport {
        var server = false
        var client = false
        val sent = mutableListOf<Triple<String, Int, ByteArray>>()
        override fun isServerReady(mac: String) = server
        override fun isClientReady(mac: String) = client
        override fun sendViaServer(mac: String, type: Int, payload: ByteArray): Boolean { sent += Triple("server", type, payload); return true }
        override fun sendViaClient(mac: String, type: Int, payload: ByteArray): Boolean { sent += Triple("client", type, payload); return true }
    }

    private val mac = "AA:BB:CC:DD:EE:FF"
    private val fake = FakeTransport()

    @Before fun setUp() {
        PeerLeChannel.resetForTest()
        PeerLeChannel.transport = fake
        PeerLeChannel.clock = { 0L }
        PeerLeChannel.authorizer = { true }
    }

    @After fun tearDown() = PeerLeChannel.resetForTest()

    @Test fun `ready needs a link and an authorized peer`() {
        assertFalse(PeerLeChannel.isReady(mac))
        fake.client = true
        assertTrue(PeerLeChannel.isReady(mac))
        PeerLeChannel.authorizer = { false }
        assertFalse(PeerLeChannel.isReady(mac))
    }

    @Test fun `small records go out unfragmented through the server first`() {
        fake.server = true; fake.client = true
        assertTrue(PeerLeChannel.send(mac, PeerGattRecords.CASE, byteArrayOf(1, 2, 3)))
        assertEquals("server", fake.sent.single().first)
        assertEquals(PeerGattRecords.CASE, fake.sent.single().second)
    }

    @Test fun `a large payload is fragmented and reassembled into one dispatch`() {
        fake.client = true
        val data = ByteArray(100_000) { it.toByte() }
        assertTrue(PeerLeChannel.send(mac, PeerGattRecords.RELAY, data))
        assertTrue(fake.sent.all { it.second == PeerGattRecords.FRAG && it.third.size <= PeerGattRecords.MAX_PAYLOAD })
        val received = mutableListOf<ByteArray>()
        PeerLeChannel.register(PeerGattRecords.RELAY) { _, payload -> received += payload }
        fake.sent.forEach { PeerLeChannel.onRecord(mac, it.second, it.third) }
        assertEquals(1, received.size)
        assertArrayEquals(data, received[0])
    }

    @Test fun `oversized payloads are refused and no link means no send`() {
        assertFalse(PeerLeChannel.send(mac, PeerGattRecords.RELAY, ByteArray(10)))
        fake.server = true
        assertFalse(PeerLeChannel.send(mac, PeerGattRecords.RELAY, ByteArray(PeerLeFragments.MAX_MESSAGE + 1)))
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `link down clears a half received message and notifies listeners`() {
        fake.client = true
        val data = ByteArray(10_000)
        PeerLeChannel.send(mac, PeerGattRecords.RELAY, data)
        val received = mutableListOf<ByteArray>()
        PeerLeChannel.register(PeerGattRecords.RELAY) { _, payload -> received += payload }
        val downs = mutableListOf<String>()
        PeerLeChannel.addLinkListener(object : PeerLeChannel.LinkListener {
            override fun onLinkUp(mac: String, central: Boolean) = Unit
            override fun onLinkDown(mac: String) { downs += mac }
        })
        PeerLeChannel.onRecord(mac, fake.sent[0].second, fake.sent[0].third)
        PeerLeChannel.onLinkDown(mac)
        fake.sent.drop(1).forEach { PeerLeChannel.onRecord(mac, it.second, it.third) }
        assertTrue(received.isEmpty())
        assertEquals(listOf(mac), downs)
    }
}
