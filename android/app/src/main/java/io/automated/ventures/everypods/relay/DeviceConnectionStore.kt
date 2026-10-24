/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class DeviceConnectionType { BT_HANDOVER, WIFI_RELAY }
data class DeviceConnectionRecord(val peerIdentity: String, val mac: String, val type: DeviceConnectionType, val pendingSync: Boolean = false)

/** Saved capabilities are independent of the Bluetooth coordination link and current playback. */
class DeviceConnectionStore(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("device_connections_v1", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val identities = context.getSharedPreferences("audio_relay_trusted_peers_v1", Context.MODE_PRIVATE)
    private fun mac(address: String) = address.uppercase(Locale.ROOT)
    private fun key(address: String) = "types_${mac(address)}"
    fun identity(address: String): String = identities.getString("peer_address_${mac(address).replace(':', '_')}", null) ?: mac(address)
    fun has(address: String, type: DeviceConnectionType) = preferences.getStringSet(key(address), emptySet())!!.contains(type.name)
    fun authorized(address: String) = has(address, DeviceConnectionType.WIFI_RELAY) && preferences.getBoolean("verified_${mac(address)}", false)

    fun handoverReady(address: String): Boolean = has(address, DeviceConnectionType.BT_HANDOVER) && !preferences.contains(handoverPendingKey(address))

    fun configuredHandoverPeers(addresses: Collection<String>): Set<String> = addresses
        .filter(::handoverReady).map(::mac).toSet()

    fun onlineHandoverPeers(onlineAddresses: Collection<String>): Set<String> = configuredHandoverPeers(onlineAddresses)

    /** Playback/call intent may acquire offline; only reachable holders can require coordination. */
    fun allowsAutomaticHandover(addresses: Collection<String>, onlineHolders: Collection<String>): Boolean {
        val eligible = configuredHandoverPeers(addresses)
        return eligible.isNotEmpty() && (onlineHolders.isEmpty() || onlineHolders.any { mac(it) in eligible })
    }

    /** Run once per legacy peer; an intentionally empty record must never be re-created by migration. */
    fun migrate(addresses: Collection<String>) = synchronized(mutationLock) {
        val edit = preferences.edit()
        var migratedHandover = false
        addresses.forEach { address ->
            if (!preferences.contains(key(address))) {
                migratedHandover = true
                val verified = identity(address) != mac(address)
                val types = mutableSetOf(DeviceConnectionType.BT_HANDOVER.name)
                if (verified) types += DeviceConnectionType.WIFI_RELAY.name
                edit.putStringSet(key(address), types).putBoolean("verified_${mac(address)}", verified)
            }
        }
        // Existing installations may already contain deliberate opt-outs. Seed
        // the marker without changing them when upgrading older peer records.
        if (migratedHandover || hasHandoverHistory()) initializeHandoverOptions(preserveChoices = true)
        edit.apply()
    }
    fun declareNew(address: String) = synchronized(mutationLock) {
        if (!preferences.contains(key(address))) preferences.edit().putStringSet(key(address), emptySet()).apply()
    }
    fun add(address: String, type: DeviceConnectionType, verified: Boolean = false) = synchronized(mutationLock) {
        val types = preferences.getStringSet(key(address), emptySet())!!.toMutableSet().apply { add(type.name) }
        val edit = preferences.edit().putStringSet(key(address), types)
        if (type == DeviceConnectionType.WIFI_RELAY) {
            edit.putBoolean("verified_${mac(address)}", verified)
            edit.putStringSet("pending_revocations", pendingRevocations() - mac(address))
        }
        if (type == DeviceConnectionType.BT_HANDOVER) {
            initializeHandoverOptions(preserveChoices = hasHandoverHistory())
            enqueueHandoverChange(edit, address, enabled = true)
            check(edit.commit()) { "Could not save BT handover setup" }
            BtHandoverSync.wake(address)
        } else edit.apply()
    }
    fun remove(address: String, type: DeviceConnectionType, propagate: Boolean = true) = synchronized(mutationLock) {
        val edit = preferences.edit().putStringSet(key(address), preferences.getStringSet(key(address), emptySet())!! - type.name)
        if (type == DeviceConnectionType.WIFI_RELAY) {
            edit.putBoolean("verified_${mac(address)}", false)
            if (propagate) edit.putStringSet("pending_revocations", pendingRevocations() + mac(address))
        }
        if (type == DeviceConnectionType.BT_HANDOVER && propagate) {
            enqueueHandoverChange(edit, address, enabled = false)
            check(edit.commit()) { "Could not save BT handover removal" }
            BtHandoverSync.wake(address)
        } else edit.apply()
    }

    private fun handoverKey(address: String) = "bt_sync_state_${mac(address)}"
    private fun handoverPendingKey(address: String) = "bt_sync_pending_${mac(address)}"
    fun handoverState(address: String): HandoverSyncState? = preferences.getString(handoverKey(address), null)?.let(HandoverSyncState::decode)

    private fun hasHandoverHistory(): Boolean = preferences.all.any { (key, value) ->
        key.startsWith("bt_sync_state_") || (key.startsWith("types_") && value is Set<*> && DeviceConnectionType.BT_HANDOVER.name in value)
    }

    private fun initializeHandoverOptions(preserveChoices: Boolean) {
        if (settings.getBoolean("bt_handover_defaults_initialized", false)) return
        val edit = settings.edit().putBoolean("bt_handover_defaults_initialized", true)
        if (!preserveChoices) {
            edit.putBoolean("cross_device_enabled", true)
                .putBoolean("takeover_when_media_start", true)
                .putBoolean("takeover_when_ringing_call", true)
        }
        // Write defaults before the capability: a crash cannot leave a completed
        // first setup with its handover options unintentionally disabled.
        check(edit.commit()) { "Could not initialize handover options" }
    }

    private fun enqueueHandoverChange(edit: SharedPreferences.Editor, address: String, enabled: Boolean) {
        val origin = preferences.getString("bt_sync_origin", null) ?: UUID.randomUUID().toString().also {
            edit.putString("bt_sync_origin", it)
        }
        val next = HandoverSyncState((handoverState(address)?.revision ?: 0L) + 1L, origin, enabled)
        edit.putString(handoverKey(address), next.encode()).putString(handoverPendingKey(address), next.encode())
    }

    fun pendingHandoverChanges(): Map<String, HandoverSyncState> = synchronized(mutationLock) {
        preferences.all.filterKeys { it.startsWith("bt_sync_pending_") }.map { (key, value) ->
            key.removePrefix("bt_sync_pending_") to HandoverSyncState.decode(value as String)
        }.toMap()
    }

    /** Commit before replying: replayed requests and delayed replies cannot undo newer user actions. */
    fun mergeRemoteHandover(address: String, remote: HandoverSyncState): HandoverSyncState = synchronized(mutationLock) {
        val local = handoverState(address)
        if (local != null && local > remote) return@synchronized local
        if (remote.enabled) initializeHandoverOptions(preserveChoices = hasHandoverHistory())
        val types = preferences.getStringSet(key(address), emptySet())!!.toMutableSet()
        if (remote.enabled) types.add(DeviceConnectionType.BT_HANDOVER.name) else types.remove(DeviceConnectionType.BT_HANDOVER.name)
        val edit = preferences.edit().putStringSet(key(address), types).putString(handoverKey(address), remote.encode())
        val pending = preferences.getString(handoverPendingKey(address), null)?.let(HandoverSyncState::decode)
        if (pending != null && pending <= remote) edit.remove(handoverPendingKey(address))
        check(edit.commit()) { "Could not save peer BT handover setup" }
        remote
    }
    fun pendingRevocations(): Set<String> = preferences.getStringSet("pending_revocations", emptySet())!!.toSet()
    fun acknowledgeRevocation(address: String) = preferences.edit().putStringSet("pending_revocations", pendingRevocations() - mac(address)).apply()
    fun records(addresses: Collection<String>): List<DeviceConnectionRecord> = addresses.flatMap { address ->
        DeviceConnectionType.entries.filter { has(address, it) }.map {
            DeviceConnectionRecord(identity(address), mac(address), it, it == DeviceConnectionType.BT_HANDOVER && !handoverReady(address))
        }
    }

    companion object {
        private val mutationLock = Any()
        /** Process-owned: a stale preference after process death must never suppress handover. */
        private val mutableEmitting = MutableStateFlow(false)
        val emittingState = mutableEmitting.asStateFlow()

        var emitting: Boolean
            get() = mutableEmitting.value
            set(value) {
                if (mutableEmitting.value == value) return
                mutableEmitting.value = value
                io.automated.ventures.everypods.utils.MediaController.onRelayHandoverSuppressionChanged(value, "sender session")
            }
    }
}
