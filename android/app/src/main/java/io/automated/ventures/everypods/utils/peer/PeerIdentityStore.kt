package io.automated.ventures.everypods.utils.peer

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** Stable installation identity and one ephemeral process identity for HELLO/SNAPSHOT. */
object PeerIdentityStore {
    data class Identity(val installationId: String, val sessionId: String)

    @Volatile private var processSessionId: String = UUID.randomUUID().toString()

    @Synchronized
    fun load(context: Context): Identity {
        val prefs = context.applicationContext.getSharedPreferences("peer_identity", Context.MODE_PRIVATE)
        var installationId = prefs.getString(KEY_INSTALLATION_ID, null)
        if (installationId == null || !isCanonicalUuid(installationId)) {
            installationId = UUID.randomUUID().toString()
            check(prefs.edit().putString(KEY_INSTALLATION_ID, installationId).commit()) { "identity_persist_failed" }
        }
        return Identity(installationId, processSessionId)
    }

    /** Custom SHA-256 UUID derived from the paired target address, never from a display name. */
    fun headsetIdFromAddress(address: String): String {
        val normalized = address.trim().uppercase()
        require(normalized.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) { "invalid_bluetooth_address" }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.US_ASCII))
        val bytes = digest.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    /** Bind the protocol identity to an already allow-listed Bluetooth address. */
    @Synchronized
    fun bindPeerAddress(context: Context, address: String, peerInstallationId: String): Boolean {
        val normalized = address.trim().uppercase()
        require(normalized.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) { "invalid_bluetooth_address" }
        require(isCanonicalUuid(peerInstallationId)) { "invalid_peer_identity" }
        val prefs = context.applicationContext.getSharedPreferences("peer_identity", Context.MODE_PRIVATE)
        val key = PEER_BINDING_PREFIX + normalized.replace(":", "")
        val previous = prefs.getString(key, null)
        if (previous == peerInstallationId) return false
        require(prefs.all.none { (otherKey, value) ->
            otherKey.startsWith(PEER_BINDING_PREFIX) && otherKey != key && value == peerInstallationId
        }) { "peer_identity_already_bound" }
        check(prefs.edit().putString(key, peerInstallationId).commit()) { "peer_binding_persist_failed" }
        return previous != null
    }

    fun peerInstallationId(context: Context, address: String): String? {
        val normalized = address.trim().uppercase()
        if (!normalized.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) return null
        val key = PEER_BINDING_PREFIX + normalized.replace(":", "")
        return context.applicationContext.getSharedPreferences("peer_identity", Context.MODE_PRIVATE)
            .getString(key, null)?.takeIf(::isCanonicalUuid)
    }

    internal fun rotateSessionForTesting(sessionId: String = UUID.randomUUID().toString()) {
        require(isCanonicalUuid(sessionId))
        processSessionId = sessionId
    }

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private const val KEY_INSTALLATION_ID = "installation_id"
    private const val PEER_BINDING_PREFIX = "peer_installation_"
}
