package io.automated.ventures.everypods.bluetooth.audio

import android.annotation.SuppressLint
import android.content.SharedPreferences
import java.util.UUID

/** Durable uncertainty marker; it grants no right to issue a command after restart. */
@SuppressLint("UseKtx") // Synchronous commit result is required before allowing an OS command.
class AudioCommandFenceStore(private val prefs: SharedPreferences) {
    data class Pending(val headset: String, val token: String, val observedConnecting: Boolean)

    @Synchronized fun load(): Pending? = try {
        if (!prefs.contains("headset") && !prefs.contains("token") && !prefs.contains("observed_connecting")) null
        else Pending(prefs.getString("headset", null).orEmpty(),
            prefs.getString("token", null).orEmpty(), prefs.getBoolean("observed_connecting", false))
    } catch (_: Exception) {
        // Corrupt state must block dispatch, not silently imply a released command.
        Pending("", "", false)
    }

    /** Commit before the OS call; failure denies dispatch. Existing uncertainty is never overwritten. */
    @Synchronized fun begin(headset: String): Pending? {
        if (headset.isBlank() || load() != null) return null
        val pending = Pending(headset, UUID.randomUUID().toString(), false)
        return if (prefs.edit().putString("headset", pending.headset).putString("token", pending.token)
                .putBoolean("observed_connecting", false).commit()) pending else null
    }

    @Synchronized fun markConnecting(token: String): Boolean {
        val pending = load() ?: return false
        if (token.isBlank() || pending.headset.isBlank() || pending.token != token) return false
        if (pending.observedConnecting) return true
        return prefs.edit().putBoolean("observed_connecting", true).commit()
    }

    /** A stale callback must never clear a newer command's marker. */
    @Synchronized fun resolve(token: String): Boolean {
        val pending = load() ?: return true
        if (token.isBlank() || pending.headset.isBlank() || pending.token != token) return false
        return prefs.edit().remove("headset").remove("token").remove("observed_connecting").commit()
    }
}
