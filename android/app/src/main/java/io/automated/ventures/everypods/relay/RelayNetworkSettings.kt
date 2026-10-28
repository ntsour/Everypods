/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import android.content.SharedPreferences
import java.util.Locale

/** A saved selection is a policy; negotiated session paths must never overwrite it. */
object RelayNetworkSettings {
    private fun key(mac: String) = "audio_relay_network_${mac.uppercase(Locale.ROOT)}"
    fun normalize(mode: String?): String = mode?.takeIf { it in setOf("auto", "wifi", "hotspot") } ?: "auto"
    fun load(preferences: SharedPreferences, mac: String): String = normalize(preferences.getString(key(mac), "auto"))
    fun save(preferences: SharedPreferences, mac: String, mode: String) =
        preferences.edit().putString(key(mac), normalize(mode)).apply()

    /** Explicit sender selection wins; an automatic sender accepts the receiver's manual override. */
    fun receiverMode(requested: String, saved: String): String =
        if (normalize(requested) == "auto") normalize(saved) else normalize(requested)
}
