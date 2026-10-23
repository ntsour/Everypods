package io.automated.ventures.everypods.utils.peer

/** Stable symmetric ordering for duplicate links; both peers select the same initiator tuple. */
data class PeerConnectionKey(val initiatorInstallationId: String, val connectionId: String) : Comparable<PeerConnectionKey> {
    override fun compareTo(other: PeerConnectionKey): Int =
        compareValuesBy(this, other, PeerConnectionKey::initiatorInstallationId, PeerConnectionKey::connectionId)
}

object PeerConnectionArbitration {
    fun preferred(keys: Collection<PeerConnectionKey>): PeerConnectionKey? = keys.minOrNull()
}
