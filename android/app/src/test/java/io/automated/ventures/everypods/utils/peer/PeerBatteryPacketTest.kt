package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.CrossDevicePackets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerBatteryPacketTest {
    private val reading = PeerBatteryPacket.Reading(
        left = 73, right = 77, case = 16,
        leftCharging = false, rightCharging = true, caseCharging = false,
        leftInEar = true, rightInEar = false,
    )

    @Test fun `round trips levels, charging and wear state`() {
        val packet = PeerBatteryPacket.encode(reading)
        assertEquals(PeerBatteryPacket.SIZE, packet.size)
        assertEquals(reading, PeerBatteryPacket.decode(packet))
    }

    @Test fun `unknown case level survives as null`() {
        val r = reading.copy(case = null)
        assertEquals(r, PeerBatteryPacket.decode(PeerBatteryPacket.encode(r)))
    }

    @Test fun `rejects malformed levels, flags and sizes`() {
        val packet = PeerBatteryPacket.encode(reading)
        assertNull(PeerBatteryPacket.decode(packet.copyOf().also { it[4] = 101 }))
        assertNull(PeerBatteryPacket.decode(packet.copyOf().also { it[7] = 0x80.toByte() }))
        assertNull(PeerBatteryPacket.decode(packet.copyOf(7)))
    }

    @Test fun `stream decoder frames the packet between control packets`() {
        val packet = PeerBatteryPacket.encode(reading)
        val stream = CrossDevicePackets.AIRPODS_CONNECTED.packet + packet + CrossDevicePackets.REQUEST_DISCONNECT.packet
        val frames = LegacyPeerStreamDecoder().feed(stream)
        assertEquals(3, frames.size)
        assertArrayEquals(packet, frames[1])
        assertArrayEquals(CrossDevicePackets.REQUEST_DISCONNECT.packet, frames[2])
    }
}
