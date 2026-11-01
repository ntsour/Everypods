package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.CrossDeviceReconnectPolicy

fun interface ElapsedClock {
    fun nowMs(): Long
}

enum class PeerOwnership { UNKNOWN, HELD, RELEASED }
enum class PeerAudioUse { UNKNOWN, IDLE, MEDIA, CALL }

/** Control-channel connectivity alone does not prove audio ownership or release. */
fun headsetAudioOwnership(profileConnected: Boolean?, aacpConnected: Boolean): PeerOwnership =
    when (profileConnected) {
        true -> PeerOwnership.HELD
        false -> if (aacpConnected) PeerOwnership.UNKNOWN else PeerOwnership.RELEASED
        null -> PeerOwnership.UNKNOWN
    }

/** Absence of a route is idle only when the profile query positively confirms it. */
fun disconnectedHeadsetAudioUse(profileDisconnected: Boolean, aacpConnected: Boolean): PeerAudioUse =
    if (profileDisconnected && !aacpConnected) PeerAudioUse.IDLE else PeerAudioUse.UNKNOWN

data class PeerObservation(
    val peerId: String,
    val headsetId: String?,
    val sessionId: String,
    val revision: Long,
    val receivedAtElapsedMs: Long,
    val ownership: PeerOwnership,
    val audioUse: PeerAudioUse,
    val protocolVersion: Int,
)

/** In-memory authority store. Persisted leases and legacy socket state are not observations. */
class PeerOwnershipStore(
    private val clock: ElapsedClock,
    private val freshnessMs: Long = 35_000L,
) {
    private var configuredPeers: Set<String> = emptySet()
    private val current = mutableMapOf<String, PeerObservation>()

    @Synchronized
    fun configure(peers: Set<String>) {
        configuredPeers = peers.mapTo(mutableSetOf()) { it.uppercase() }
        current.keys.retainAll(configuredPeers)
    }

    @Synchronized
    fun observe(observation: PeerObservation): Boolean {
        val peer = observation.peerId.uppercase()
        if (peer !in configuredPeers || observation.protocolVersion != 2) return false
        if (observation.revision < 0L || observation.receivedAtElapsedMs < 0L) return false
        if (observation.sessionId.isBlank()) return false
        val now = clock.nowMs()
        if (!CrossDeviceReconnectPolicy.observationIsFresh(observation.receivedAtElapsedMs, now, freshnessMs)) return false
        val prior = current[peer]
        if (prior != null && prior.sessionId == observation.sessionId) {
            if (observation.revision < prior.revision) return false
            if (observation.revision == prior.revision) {
                if (observation.receivedAtElapsedMs < prior.receivedAtElapsedMs) return false
                if (prior.copy(receivedAtElapsedMs = observation.receivedAtElapsedMs) != observation.copy(peerId = peer)) return false
            }
        }
        current[peer] = observation.copy(peerId = peer)
        return true
    }

    @Synchronized
    fun invalidate(peerId: String) {
        current.remove(peerId.uppercase())
    }

    @Synchronized
    fun clear() {
        current.clear()
    }

    @Synchronized
    fun observations(): Map<String, PeerObservation> = current.toMap()

    @Synchronized
    fun allConfiguredPeersFresh(headsetId: String? = null): Boolean {
        val now = clock.nowMs()
        return configuredPeers.all { peer ->
            val value = current[peer] ?: return@all false
            value.headsetId != null && (headsetId == null || value.headsetId == headsetId) &&
                CrossDeviceReconnectPolicy.observationIsFresh(value.receivedAtElapsedMs, now, freshnessMs)
        }
    }

    /** Atomic authoritative lookup; raw observations may be stale and are diagnostics only. */
    @Synchronized
    fun freshObservations(headsetId: String): Map<String, PeerObservation>? =
        if (allConfiguredPeersFresh(headsetId)) current.toMap() else null

    /** True only when every configured peer reports this headset released and no active use. */
    @Synchronized
    fun allPeersRelease(headsetId: String): Boolean {
        if (!allConfiguredPeersFresh(headsetId)) return false
        return configuredPeers.all { peer ->
            val value = current[peer] ?: return@all false
            value.ownership == PeerOwnership.RELEASED && value.audioUse == PeerAudioUse.IDLE
        }
    }
}
