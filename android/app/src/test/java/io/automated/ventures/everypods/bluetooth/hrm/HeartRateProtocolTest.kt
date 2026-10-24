package io.automated.ventures.everypods.bluetooth.hrm

import org.junit.Assert.*
import org.junit.Test

class HeartRateProtocolTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Independent wire fixtures: SensorDataWX.command -> HEARTRATEv2, 18-byte measurement.
    private val measurement = hex("0400040017000000100018003a1608141a1200c8" + "00".repeat(16))
    private val movement = hex("0400040017000000100040003a3e08101a3a" + "00".repeat(58))

    @Test fun `connect packet matches working LibrePods handshake`() {
        assertArrayEquals(hex("00000400010003000000000000000000"),
            io.automated.ventures.everypods.bluetooth.AACPManager().createHandshakePacket())
    }

    @Test fun `acknowledgments expose metadata without creating readings`() {
        for ((wire, kind) in listOf("08074a020854" to "start_ack", "080862020814" to "command_ack")) {
            val payload = hex(wire)
            val frame = hex("04000400170000001000") + byteArrayOf(payload.size.toByte(), 0) + payload
            val seen = mutableListOf<HeartRateProtocol.Metadata>()
            val result = HeartRateProtocol.decode(frame, seen::add)
            assertTrue(result is HeartRateProtocol.Result.Acknowledgment)
            assertEquals(kind, seen.single().kind)
            assertEquals(if (kind == "start_ack") 84 else 20, seen.single().service)
            assertEquals(if (kind == "start_ack") 7 else 8, seen.single().sequence)
        }
    }

    @Test fun `start encoding matches one second upstream sensor request`() {
        assertArrayEquals(hex("1700000010000d00420b081310021a050140420f00"), HeartRateProtocol.command(19, 1_000_000, 0))
        assertArrayEquals(hex("1700000010000f000801420b085410021a050100000000"), HeartRateProtocol.command(84, 0, 1))
    }

    @Test fun `firmware selection handles unknown multidigit and build suffixes`() {
        assertEquals(19, HeartRateProtocol.serviceForFirmware(""))
        assertEquals(19, HeartRateProtocol.serviceForFirmware("unknown"))
        assertEquals(19, HeartRateProtocol.serviceForFirmware("8B39"))
        assertEquals(84, HeartRateProtocol.serviceForFirmware("9A348"))
        assertEquals(84, HeartRateProtocol.serviceForFirmware("9442752"))
        assertEquals(84, HeartRateProtocol.serviceForFirmware(" 9A348"))
        assertEquals(84, HeartRateProtocol.serviceForFirmware("10A1"))
    }

    @Test fun `unsigned measurement above 127 is accepted for both services`() {
        assertEquals(HeartRateProtocol.Result.Sample(200), HeartRateProtocol.decode(measurement))
        val legacy = measurement.copyOf().apply { this[15] = 19 }
        assertEquals(HeartRateProtocol.Result.Sample(200), HeartRateProtocol.decode(legacy))
    }

    @Test fun `bad flags zero bpm length and protobuf are rejected`() {
        assertTrue(HeartRateProtocol.decode(measurement.copyOf().apply { this[lastIndex] = 1 }) is HeartRateProtocol.Result.Rejected)
        assertTrue(HeartRateProtocol.decode(measurement.copyOf().apply { this[19] = 0 }) is HeartRateProtocol.Result.Rejected)
        assertTrue(HeartRateProtocol.decode(measurement.dropLast(1).toByteArray()) is HeartRateProtocol.Result.Rejected)
        val invalidProto = hex("040004001700000010000100ff")
        assertTrue(HeartRateProtocol.decode(invalidProto) is HeartRateProtocol.Result.Rejected)
    }

    @Test fun `every split position reassembles without changing movement frames`() {
        for (frame in listOf(measurement, movement)) {
            for (split in 1 until frame.size) {
                val frames = BuddyFrameDecoder()
                assertTrue(frames.feed(frame.copyOfRange(0, split)).isEmpty())
                assertArrayEquals(frame, frames.feed(frame.copyOfRange(split, frame.size)).single())
            }
        }
        assertEquals(HeartRateProtocol.Result.OtherSensor, HeartRateProtocol.decode(movement))
    }

    @Test fun `consecutive sensor frames and trailing normal traffic are separated`() {
        val normal = hex("0400040009000d02000000")
        val result = BuddyFrameDecoder().feed(measurement + movement + normal)
        assertEquals(3, result.size)
        assertArrayEquals(measurement, result[0])
        assertArrayEquals(movement, result[1])
        assertArrayEquals(normal, result[2])
    }

    @Test fun `disconnect and malformed envelopes do not poison later frames`() {
        val rejected = mutableListOf<String>()
        val decoder = BuddyFrameDecoder(rejected::add)
        decoder.feed(measurement.take(15).toByteArray())
        decoder.reset()
        assertArrayEquals(measurement, decoder.feed(measurement).single())
        assertTrue(decoder.feed(hex("04000400170000001000ffff")).isEmpty())
        assertEquals(listOf("oversized_buddy_frame"), rejected)
        assertArrayEquals(measurement, decoder.feed(measurement).single())
    }

    @Test fun `existing manager dispatches movement unchanged and short HRM to its own callback`() {
        val received = mutableListOf<Pair<String, Any?>>()
        val callback = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(io.automated.ventures.everypods.bluetooth.AACPManager.PacketCallback::class.java)
        ) { _, method, args -> received += method.name to args?.firstOrNull(); null }
            as io.automated.ventures.everypods.bluetooth.AACPManager.PacketCallback
        val manager = io.automated.ventures.everypods.bluetooth.AACPManager()
        manager.setPacketCallback(callback)
        manager.receivePacket(movement + measurement)
        assertEquals(listOf("onHeadTrackingReceived", "onHeartRateSample"), received.map { it.first })
        assertArrayEquals(movement, received[0].second as ByteArray)
        assertEquals(200, received[1].second)
        manager.receivePacket(measurement.copyOfRange(0, 16))
        manager.resetHeartRateConnection()
        received.clear()
        manager.receivePacket(measurement)
        assertEquals(listOf("onHeartRateSample"), received.map { it.first })
        manager.receivePacket(measurement.copyOfRange(0, 16))
        manager.disconnected()
        received.clear()
        manager.receivePacket(measurement)
        assertEquals(listOf("onHeartRateSample"), received.map { it.first })
    }
}
