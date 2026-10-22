/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NamedRelayAudioPreset(val id: String, val name: String, val tuning: RelayAudioTuning)

/** Device-local snapshots available to every connection; loading still applies per peer. */
class RelayNamedAudioPresets(private val preferences: SharedPreferences) {
    fun load(): List<NamedRelayAudioPreset> {
        val array = runCatching { JSONArray(preferences.getString(KEY, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val name = item.getString("name").trim().also { require(it.isNotEmpty() && it.length <= 60) }
                NamedRelayAudioPreset(item.getString("id"), name,
                    RelayAudioTuning.decodeWire(Base64.decode(item.getString("profile"), Base64.NO_WRAP)))
            }.getOrNull()
        }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    fun save(name: String, tuning: RelayAudioTuning): List<NamedRelayAudioPreset> {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 60)
        val existing = load()
        require(existing.none { it.name.equals(cleanName, ignoreCase = true) })
        persist(existing + NamedRelayAudioPreset(UUID.randomUUID().toString(), cleanName, tuning.validated()))
        return load()
    }

    fun delete(id: String): List<NamedRelayAudioPreset> {
        persist(load().filterNot { it.id == id })
        return load()
    }

    private fun persist(presets: List<NamedRelayAudioPreset>) {
        val array = JSONArray()
        presets.forEach { preset -> array.put(JSONObject().put("id", preset.id).put("name", preset.name)
            .put("profile", Base64.encodeToString(preset.tuning.encodeWire(), Base64.NO_WRAP))) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    companion object { private const val KEY = "audio_relay_named_presets_v1" }
}
