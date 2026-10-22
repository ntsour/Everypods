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
    private val address = "AA:BB:CC:DD:EE:01"
    private lateinit var context: Context
    private lateinit var store: DeviceConnectionStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device_connections_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audio_relay_trusted_peers_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store = DeviceConnectionStore(context)
    }
    @Test fun legacyBluetoothPeerMigratesOnceWithoutInventingWifiTrust() {
        store.migrate(listOf(address))
        assertTrue(store.has(address, DeviceConnectionType.BT_HANDOVER))
        assertFalse(store.has(address, DeviceConnectionType.WIFI_RELAY))
        store.remove(address, DeviceConnectionType.BT_HANDOVER)
        store.migrate(listOf(address))
        assertTrue(store.records(listOf(address)).isEmpty())
    }
    @Test fun existingVerifiedPeerRetainsBothConnectionsAndStableIdentity() {
        val peerId = "a".repeat(64)
        context.getSharedPreferences("audio_relay_trusted_peers_v1", Context.MODE_PRIVATE).edit()
            .putString("peer_address_AA_BB_CC_DD_EE_01", peerId).commit()
        store.migrate(listOf(address))
        assertEquals(setOf(DeviceConnectionType.BT_HANDOVER, DeviceConnectionType.WIFI_RELAY), store.records(listOf(address)).map { it.type }.toSet())
        assertTrue(store.authorized(address))
        assertTrue(store.records(listOf(address)).all { it.peerIdentity == peerId })
    }
    @Test fun newWifiConnectionDoesNotCreateBluetoothHandoverAndRequiresVerification() {
        store.declareNew(address)
        store.add(address, DeviceConnectionType.WIFI_RELAY)
        store.migrate(listOf(address))
        assertFalse(store.has(address, DeviceConnectionType.BT_HANDOVER))
        assertFalse(store.authorized(address))
        store.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        assertTrue(store.authorized(address))
        assertEquals(1, store.records(listOf(address)).size)
    }
    @Test fun removingBluetoothPreservesVerifiedWifi() {
        store.add(address, DeviceConnectionType.BT_HANDOVER)
        store.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        store.remove(address, DeviceConnectionType.BT_HANDOVER)
        assertTrue(store.authorized(address))
        assertFalse(store.has(address, DeviceConnectionType.BT_HANDOVER))
        assertTrue(store.pendingRevocations().isEmpty())
    }
    @Test fun offlineWifiRemovalRevokesImmediatelyAndPersistsUntilAcknowledged() {
        store.add(address, DeviceConnectionType.BT_HANDOVER)
        store.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        store.remove(address.lowercase(), DeviceConnectionType.WIFI_RELAY)
        val reopened = DeviceConnectionStore(context)
        assertFalse(reopened.authorized(address))
        assertTrue(reopened.has(address, DeviceConnectionType.BT_HANDOVER))
        assertEquals(setOf(address), reopened.pendingRevocations())
        reopened.acknowledgeRevocation(address.lowercase())
        assertTrue(store.pendingRevocations().isEmpty())
    }
    @Test fun receivedRevocationDoesNotEchoOrRemoveBluetooth() {
        store.add(address, DeviceConnectionType.BT_HANDOVER)
        store.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        store.remove(address, DeviceConnectionType.WIFI_RELAY, propagate = false)
        assertTrue(store.has(address, DeviceConnectionType.BT_HANDOVER))
        assertFalse(store.authorized(address))
        assertTrue(store.pendingRevocations().isEmpty())
    }
}
