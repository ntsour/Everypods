/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class DeviceConnectionType { BT_HANDOVER, WIFI_RELAY }
data class DeviceConnectionRecord(val peerIdentity: String, val mac: String, val type: DeviceConnectionType)

/** Saved capabilities are independent of the Bluetooth coordination link and current playback. */
class DeviceConnectionStore(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("device_connections_v1", Context.MODE_PRIVATE)
    private val identities = context.getSharedPreferences("audio_relay_trusted_peers_v1", Context.MODE_PRIVATE)
    private fun mac(address: String) = address.uppercase(Locale.ROOT)
    private fun key(address: String) = "types_${mac(address)}"
    fun identity(address: String): String = identities.getString("peer_address_${mac(address).replace(':', '_')}", null) ?: mac(address)
    fun has(address: String, type: DeviceConnectionType) = preferences.getStringSet(key(address), emptySet())!!.contains(type.name)
    fun authorized(address: String) = has(address, DeviceConnectionType.WIFI_RELAY) && preferences.getBoolean("verified_${mac(address)}", false)

    /** Run once per legacy peer; an intentionally empty record must never be re-created by migration. */
    @Synchronized fun migrate(addresses: Collection<String>) {
        val edit = preferences.edit()
        addresses.forEach { address ->
            if (!preferences.contains(key(address))) {
                val verified = identity(address) != mac(address)
                val types = mutableSetOf(DeviceConnectionType.BT_HANDOVER.name)
                if (verified) types += DeviceConnectionType.WIFI_RELAY.name
                edit.putStringSet(key(address), types).putBoolean("verified_${mac(address)}", verified)
            }
        }
        edit.apply()
    }
    fun declareNew(address: String) {
        if (!preferences.contains(key(address))) preferences.edit().putStringSet(key(address), emptySet()).apply()
    }
    @Synchronized fun add(address: String, type: DeviceConnectionType, verified: Boolean = false) {
        val types = preferences.getStringSet(key(address), emptySet())!!.toMutableSet().apply { add(type.name) }
        val edit = preferences.edit().putStringSet(key(address), types)
        if (type == DeviceConnectionType.WIFI_RELAY) {
            edit.putBoolean("verified_${mac(address)}", verified)
            edit.putStringSet("pending_revocations", pendingRevocations() - mac(address))
        }
        edit.apply()
    }
    @Synchronized fun remove(address: String, type: DeviceConnectionType, propagate: Boolean = true) {
        val edit = preferences.edit().putStringSet(key(address), preferences.getStringSet(key(address), emptySet())!! - type.name)
        if (type == DeviceConnectionType.WIFI_RELAY) {
            edit.putBoolean("verified_${mac(address)}", false)
            if (propagate) edit.putStringSet("pending_revocations", pendingRevocations() + mac(address))
        }
        edit.apply()
    }
    fun pendingRevocations(): Set<String> = preferences.getStringSet("pending_revocations", emptySet())!!.toSet()
    fun acknowledgeRevocation(address: String) = preferences.edit().putStringSet("pending_revocations", pendingRevocations() - mac(address)).apply()
    fun records(addresses: Collection<String>): List<DeviceConnectionRecord> = addresses.flatMap { address ->
        DeviceConnectionType.entries.filter { has(address, it) }.map { DeviceConnectionRecord(identity(address), mac(address), it) }
    }

    companion object {
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
