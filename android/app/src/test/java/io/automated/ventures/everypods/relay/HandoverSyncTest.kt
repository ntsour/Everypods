package io.automated.ventures.everypods.relay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandoverSyncTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private lateinit var firstContext: Context
    private lateinit var secondContext: Context
    private lateinit var first: DeviceConnectionStore
    private lateinit var second: DeviceConnectionStore

    private class DeviceContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("${prefix}_$name", mode)
    }

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        firstContext = DeviceContext(context, "first")
        secondContext = DeviceContext(context, "second")
        first = DeviceConnectionStore(firstContext)
        second = DeviceConnectionStore(secondContext)
        first.preferences.edit().clear().commit()
        second.preferences.edit().clear().commit()
        firstContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        secondContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun deliver(from: DeviceConnectionStore, to: DeviceConnectionStore) {
        val packet = HandoverSyncState.decode(from.pendingHandoverChanges().getValue(address).encode())
        val response = to.mergeRemoteHandover(address, packet)
        from.mergeRemoteHandover(address, HandoverSyncState.decode(response.encode()))
    }

    @Test fun `one explicit setup creates both app entries and waits for acknowledgment`() {
        first.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        second.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        assertFalse(first.handoverReady(address))
        assertFalse(second.has(address, DeviceConnectionType.BT_HANDOVER))
        deliver(first, second)
        assertTrue(first.handoverReady(address))
        assertTrue(second.handoverReady(address))
        assertTrue(first.authorized(address))
        assertTrue(second.authorized(address))
        assertTrue(first.pendingHandoverChanges().isEmpty())
        assertTrue(second.pendingHandoverChanges().isEmpty())
    }

    @Test fun `offline removal persists across restart and removes the other entry after reconnect`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        assertFalse(first.handoverReady(address))
        assertTrue(second.handoverReady(address))
        val reloaded = DeviceConnectionStore(firstContext)
        assertFalse(reloaded.pendingHandoverChanges().getValue(address).enabled)
        deliver(reloaded, second)
        assertFalse(second.has(address, DeviceConnectionType.BT_HANDOVER))
        assertTrue(reloaded.pendingHandoverChanges().isEmpty())
    }

    @Test fun `delayed creation acknowledgment cannot erase a newer pending deletion`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        val creation = first.handoverState(address)!!
        val reply = second.mergeRemoteHandover(address, creation)
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        val removal = first.handoverState(address)
        first.mergeRemoteHandover(address, reply)
        assertEquals(removal, first.pendingHandoverChanges()[address])
        assertFalse(first.handoverReady(address))
        deliver(first, second)
        assertFalse(second.handoverReady(address))
    }

    @Test fun `replayed creation cannot recreate a deleted connection`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        val oldCreation = first.handoverState(address)!!
        deliver(first, second)
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        assertFalse(second.mergeRemoteHandover(address, oldCreation).enabled)
        assertFalse(second.has(address, DeviceConnectionType.BT_HANDOVER))
        assertTrue(second.pendingHandoverChanges().isEmpty())
    }

    @Test fun `concurrent add and delete converge to deletion regardless of delivery order`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        second.remove(address, DeviceConnectionType.BT_HANDOVER)
        val create = first.handoverState(address)!!
        val remove = second.handoverState(address)!!
        first.mergeRemoteHandover(address, remove)
        val winner = second.mergeRemoteHandover(address, create)
        second.mergeRemoteHandover(address, first.handoverState(address)!!)
        assertEquals(remove, winner)
        assertEquals(first.handoverState(address), second.handoverState(address))
        assertFalse(first.handoverReady(address))
        assertFalse(second.handoverReady(address))
        assertTrue(first.pendingHandoverChanges().isEmpty())
        assertTrue(second.pendingHandoverChanges().isEmpty())
    }

    @Test fun `explicitly adding again after deletion creates a newer shared connection`() {
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        val removedRevision = first.handoverState(address)!!.revision
        second.add(address, DeviceConnectionType.BT_HANDOVER)
        deliver(second, first)
        assertTrue(first.handoverReady(address))
        assertTrue(second.handoverReady(address))
        assertTrue(first.handoverState(address)!!.revision > removedRevision)
    }

    @Test fun `duplicate requests and replies are idempotent and do not create echo changes`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        val request = first.handoverState(address)!!
        repeat(3) {
            val reply = second.mergeRemoteHandover(address, request)
            first.mergeRemoteHandover(address, reply)
        }
        assertEquals(request, first.handoverState(address))
        assertEquals(request, second.handoverState(address))
        assertTrue(first.pendingHandoverChanges().isEmpty())
        assertTrue(second.pendingHandoverChanges().isEmpty())
    }

    @Test fun `acknowledging one peer leaves other pending changes alone`() {
        val other = "AA:BB:CC:DD:EE:02"
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        first.remove(other, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        assertEquals(setOf(other), first.pendingHandoverChanges().keys)
    }

    @Test fun `malformed revisions origins and unsupported versions are rejected`() {
        val origin = "00000000-0000-4000-8000-000000000001"
        assertThrows(IllegalArgumentException::class.java) { HandoverSyncState(0, origin, true) }
        assertThrows(IllegalArgumentException::class.java) { HandoverSyncState(1, "invalid", true) }
        assertThrows(IllegalArgumentException::class.java) { HandoverSyncState.decode(HandoverSyncState(1, origin, true).encode().replace("\"version\":1", "\"version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { HandoverSyncState.decode("{\"version\":1,\"revision\":1.5,\"origin\":\"$origin\",\"enabled\":true}") }
    }

    @Test fun `setup bootstraps peer membership and final removal preserves the deletion record`() {
        assertTrue(BtHandoverSync.updateMembership(firstContext, address, keep = true))
        assertFalse(BtHandoverSync.updateMembership(firstContext, address.lowercase(), keep = true))
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        assertTrue(BtHandoverSync.updateMembership(firstContext, address, keep = false))
        assertEquals("[]", firstContext.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("cross_device_peers", null))
        assertFalse(first.handoverState(address)!!.enabled)
        assertFalse(first.pendingHandoverChanges().getValue(address).enabled)
    }

    @Test fun `adding a new device preserves existing legacy membership`() {
        val previous = "AA:BB:CC:DD:EE:02"
        val prefs = firstContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("cross_device_peer_mac", previous).commit()
        BtHandoverSync.updateMembership(firstContext, address, keep = true)
        val peers = org.json.JSONArray(prefs.getString("cross_device_peers", null))
        assertEquals(setOf(previous, address), (0 until peers.length()).map { peers.getString(it) }.toSet())
        assertFalse(prefs.contains("cross_device_peer_mac"))
    }

    private val handoverOptions = listOf("cross_device_enabled", "takeover_when_media_start", "takeover_when_ringing_call")

    private fun setOptions(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        handoverOptions.forEach { edit.putBoolean(it, enabled) }
        edit.commit()
    }

    private fun assertOptions(context: Context, enabled: Boolean) {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        handoverOptions.forEach { assertEquals(it, enabled, settings.getBoolean(it, !enabled)) }
    }

    @Test fun `first BT setup enables every handover option on both devices`() {
        setOptions(firstContext, false)
        setOptions(secondContext, false)
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        assertOptions(firstContext, true)
        assertOptions(secondContext, false)
        deliver(first, second)
        assertOptions(firstContext, true)
        assertOptions(secondContext, true)
    }

    @Test fun `later peers acknowledgments and readding after restart preserve opt outs`() {
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        // Opting out while setup is pending must survive the acknowledgment.
        setOptions(firstContext, false)
        deliver(first, second)
        assertOptions(firstContext, false)
        first.add("AA:BB:CC:DD:EE:02", DeviceConnectionType.BT_HANDOVER)
        assertOptions(firstContext, false)
        first.remove(address, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        val reloaded = DeviceConnectionStore(firstContext)
        reloaded.add(address, DeviceConnectionType.BT_HANDOVER)
        deliver(reloaded, second)
        assertOptions(firstContext, false)
    }

    @Test fun `upgrading existing BT entries preserves prior opt outs`() {
        setOptions(firstContext, false)
        first.preferences.edit().putStringSet("types_$address", setOf(DeviceConnectionType.BT_HANDOVER.name)).commit()
        first.migrate(listOf(address))
        first.add(address, DeviceConnectionType.BT_HANDOVER)
        deliver(first, second)
        assertOptions(firstContext, false)
        assertOptions(secondContext, true)
    }

    @Test fun `Wi-Fi setup does not opt the user into BT handover`() {
        setOptions(firstContext, false)
        first.add(address, DeviceConnectionType.WIFI_RELAY, verified = true)
        first.migrate(listOf(address))
        assertOptions(firstContext, false)
        assertFalse(firstContext.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("bt_handover_defaults_initialized"))
    }
}
