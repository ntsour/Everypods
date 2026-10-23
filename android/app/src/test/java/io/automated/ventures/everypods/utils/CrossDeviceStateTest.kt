package io.automated.ventures.everypods.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.relay.DeviceConnectionStore
import io.automated.ventures.everypods.relay.DeviceConnectionType

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Source-aware packet handling: ownership is attributed to the exact peer MAC the
 * packet came from. This is the Phase 2 correctness property that lets the courtesy
 * filter and "pods are elsewhere" logic work with 3+ devices — a peer announcing
 * ownership no longer clobbers a single shared flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrossDeviceStateTest {

    private val aa = "AA:AA:AA:AA:AA:AA"
    private val bb = "BB:BB:BB:BB:BB:BB"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        CrossDevice.resetForTesting()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        DeviceConnectionStore(context).apply {
            preferences.edit().clear().commit()
            add(aa, DeviceConnectionType.BT_HANDOVER)
            add(bb, DeviceConnectionType.BT_HANDOVER)
            mergeRemoteHandover(aa, handoverState(aa)!!)
            mergeRemoteHandover(bb, handoverState(bb)!!)
        }
    }

    @After
    fun tearDown() {
        CrossDevice.resetForTesting()
    }

    @Test
    fun `AIRPODS_CONNECTED adds the source to holders`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)

        assertEquals(setOf(aa), CrossDevice.holders.toSet())
        assertTrue(CrossDevice.isAvailable)
    }

    @Test
    fun `second peer connecting does not clobber the first`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, bb)

        assertEquals(setOf(aa, bb), CrossDevice.holders.toSet())
    }

    @Test
    fun `AIRPODS_DISCONNECTED removes only the source, others remain`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, bb)

        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet, aa)

        assertEquals(setOf(bb), CrossDevice.holders.toSet())
        assertTrue(CrossDevice.isAvailable)
    }

    @Test
    fun `last holder disconnecting clears availability`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_DISCONNECTED.packet, aa)

        assertTrue(CrossDevice.holders.isEmpty())
        assertFalse(CrossDevice.isAvailable)
    }

    @Test
    fun `relayed AIRPODS_DATA marks the source as holder`() {
        // header + a trivial payload; processRelayedPacket no-ops without a service,
        // but the holder attribution must still happen.
        val packet = CrossDevicePackets.AIRPODS_DATA_HEADER.packet + byteArrayOf(0x01, 0x02)
        CrossDevice.processPacket(packet, aa)

        assertTrue(CrossDevice.holders.contains(aa))
    }

    @Test
    fun `unrecognized packet is ignored without crashing or changing holders`() {
        CrossDevice.processPacket(CrossDevicePackets.AIRPODS_CONNECTED.packet, aa)

        CrossDevice.processPacket(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F), bb)

        assertEquals(setOf(aa), CrossDevice.holders.toSet())
    }

    @Test
    fun `ignored REQUEST_HANDOVER with pods in case does not sticky-hold peer`() {
        CrossDevice.inEarOverrideForTesting = false

        CrossDevice.processPacket(CrossDevicePackets.REQUEST_HANDOVER.packet, aa, context)

        assertFalse(CrossDevice.holders.contains(aa))
        assertTrue(CrossDevice.holders.isEmpty())
    }

    @Test
    fun `accepted REQUEST_HANDOVER with pod in ear adds peer to holders`() {
        CrossDevice.inEarOverrideForTesting = true

        CrossDevice.processPacket(CrossDevicePackets.REQUEST_HANDOVER.packet, aa, context)

        assertEquals(setOf(aa), CrossDevice.holders.toSet())
    }

    @Test
    fun `ignored REQUEST_DISCONNECT with pods in case does not sticky-hold peer`() {
        CrossDevice.inEarOverrideForTesting = false

        CrossDevice.processPacket(CrossDevicePackets.REQUEST_DISCONNECT.packet, bb, context)

        assertFalse(CrossDevice.holders.contains(bb))
    }

    @Test
    fun `removing BT handover rejects both handover requests while Wi-Fi relay remains`() {
        CrossDevice.inEarOverrideForTesting = true
        DeviceConnectionStore(context).apply {
            add(aa, DeviceConnectionType.WIFI_RELAY, verified = true)
            remove(aa, DeviceConnectionType.BT_HANDOVER)
        }
        CrossDevice.processPacket(CrossDevicePackets.REQUEST_HANDOVER.packet, aa, context)
        CrossDevice.processPacket(CrossDevicePackets.REQUEST_DISCONNECT.packet, aa, context)
        assertTrue(CrossDevice.holders.isEmpty())
        assertTrue(DeviceConnectionStore(context).authorized(aa))
    }

    @Test
    fun `disabling handover rejects a saved BT peer request`() {
        CrossDevice.inEarOverrideForTesting = true
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("cross_device_enabled", false).commit()
        CrossDevice.processPacket(CrossDevicePackets.REQUEST_HANDOVER.packet, aa, context)
        assertTrue(CrossDevice.holders.isEmpty())
    }
}
