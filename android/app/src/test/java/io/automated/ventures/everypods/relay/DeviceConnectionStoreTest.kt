package io.automated.ventures.everypods.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceConnectionStoreTest {
    private lateinit var store: DeviceConnectionStore
    private val first = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"

    @Before fun setup() {
        store = DeviceConnectionStore(ApplicationProvider.getApplicationContext<Context>())
        store.preferences.edit().clear().commit()
    }

    @Test fun `only a saved BT handover peer that is online unlocks handover`() {
        assertTrue(store.onlineHandoverPeers(listOf(first)).isEmpty())
        store.add(first, DeviceConnectionType.WIFI_RELAY, verified = true)
        assertTrue(store.onlineHandoverPeers(listOf(first)).isEmpty())
        store.add(second, DeviceConnectionType.BT_HANDOVER)
        assertTrue(store.onlineHandoverPeers(listOf(second)).isEmpty())
        store.mergeRemoteHandover(second, store.handoverState(second)!!)
        assertTrue(store.onlineHandoverPeers(listOf(first)).isEmpty())
        assertTrue(store.onlineHandoverPeers(emptyList()).isEmpty())
        assertEquals(setOf(second), store.onlineHandoverPeers(listOf(first, second.lowercase())))
    }

    @Test fun `acknowledged BT setup permits playback takeover with every peer offline`() {
        assertFalse(store.allowsAutomaticHandover(listOf(first), emptyList()))
        store.add(first, DeviceConnectionType.WIFI_RELAY, verified = true)
        assertFalse(store.allowsAutomaticHandover(listOf(first), emptyList()))
        store.add(first, DeviceConnectionType.BT_HANDOVER)
        assertFalse(store.allowsAutomaticHandover(listOf(first), emptyList()))
        store.mergeRemoteHandover(first, store.handoverState(first)!!)
        assertTrue(store.onlineHandoverPeers(emptyList()).isEmpty())
        assertTrue(store.allowsAutomaticHandover(listOf(first.lowercase()), emptyList()))
        val reloaded = DeviceConnectionStore(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(reloaded.allowsAutomaticHandover(listOf(first), emptyList()))
        store.remove(first, DeviceConnectionType.BT_HANDOVER)
        assertFalse(store.allowsAutomaticHandover(listOf(first), emptyList()))
    }

    @Test fun `reachable holder still requires a handover capability`() {
        store.add(first, DeviceConnectionType.BT_HANDOVER)
        store.mergeRemoteHandover(first, store.handoverState(first)!!)
        store.add(second, DeviceConnectionType.WIFI_RELAY, verified = true)
        assertFalse(store.allowsAutomaticHandover(listOf(first, second), listOf(second)))
        assertTrue(store.allowsAutomaticHandover(listOf(first, second), listOf(first.lowercase())))
        assertTrue(store.allowsAutomaticHandover(listOf(first, second), emptyList()))
        assertFalse(store.allowsAutomaticHandover(emptyList(), emptyList()))
    }

    @Test fun `removing BT takes effect immediately while relay authorization remains`() {
        store.add(first, DeviceConnectionType.BT_HANDOVER)
        store.add(first, DeviceConnectionType.WIFI_RELAY, verified = true)
        store.mergeRemoteHandover(first, store.handoverState(first)!!)
        assertEquals(setOf(first), store.onlineHandoverPeers(listOf(first)))
        store.remove(first, DeviceConnectionType.BT_HANDOVER)
        assertTrue(store.onlineHandoverPeers(listOf(first)).isEmpty())
        assertTrue(store.authorized(first))
        assertTrue(store.pendingRevocations().isEmpty())
    }

    @Test fun `Wi-Fi removal queues revocation and preserves BT handover`() {
        store.add(first, DeviceConnectionType.BT_HANDOVER)
        store.add(first, DeviceConnectionType.WIFI_RELAY, verified = true)
        store.mergeRemoteHandover(first, store.handoverState(first)!!)
        store.remove(first, DeviceConnectionType.WIFI_RELAY)
        assertFalse(store.authorized(first))
        assertEquals(setOf(first), store.pendingRevocations())
        assertEquals(setOf(first), store.onlineHandoverPeers(listOf(first)))
        // A new store instance models reading the durable outbox after restart.
        val reloaded = DeviceConnectionStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(setOf(first), reloaded.pendingRevocations())
        reloaded.acknowledgeRevocation(first)
        assertTrue(store.pendingRevocations().isEmpty())
    }
}
