package io.automated.ventures.everypods.utils

/** Keeps passive peer recovery quiet while the local phone is carrying audio. */
object CrossDeviceReconnectPolicy {
    fun observationIsFresh(observedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        observedAtMs >= 0L && nowMs >= observedAtMs && maxAgeMs >= 0L && nowMs - observedAtMs <= maxAgeMs

    fun allPeersHaveFreshStatus(
        configuredPeers: Set<String>,
        connectedPeers: Set<String>,
        statusAtElapsedMs: Map<String, Long>,
        nowElapsedMs: Long,
        maxAgeMs: Long,
    ): Boolean {
        val connected = connectedPeers.mapTo(mutableSetOf()) { it.uppercase() }
        val statuses = statusAtElapsedMs.mapKeys { it.key.uppercase() }
        return configuredPeers.all { peer ->
            val key = peer.uppercase()
            key in connected && statuses[key]?.let {
                observationIsFresh(it, nowElapsedMs, maxAgeMs)
            } == true
        }
    }

    /** A held-headset attempt is limited to scheduled recovery or an explicit user retry. */
    fun mayAttempt(
        localAirPodsConnected: Boolean,
        manualAttemptRequested: Boolean,
        reachabilityProbeRequested: Boolean = false,
    ): Boolean = !localAirPodsConnected || manualAttemptRequested || reachabilityProbeRequested

    /** Only the remembered holder may recover without any reachable peers. */
    fun mayRecoverOfflineLastHolder(leaseHolder: Boolean, leaseEverSet: Boolean,
                                   anyPeerReachable: Boolean, peerHolding: Boolean): Boolean =
        leaseHolder && leaseEverSet && !anyPeerReachable && !peerHolding

    /** Shared passive acquisition additionally needs a live distributed reservation. */
    fun mayPassivelyAcquire(
        shared: Boolean,
        peersSynchronized: Boolean,
        peerHolding: Boolean,
        reservationGranted: Boolean = false,
    ): Boolean = !shared || (peersSynchronized && !peerHolding && reservationGranted)
}
