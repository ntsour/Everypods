/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

/**
 * Tells each linked computer whether another device holds the AirPods in a call.
 *
 * Computers never link to each other (the Mac and Windows are both Bluetooth LE centrals of the phones), so a
 * Mac could not see that the PC holds the AirPods in a Meet call and took them for a YouTube video (28 Nov).
 * The phones see every computer's v2 snapshot, so they pass the call on: OTHER_PEER_CALL_ACTIVE / _IDLE, legacy
 * packets a computer that doesn't know them ignores. A computer treats "active" like a phone's own call: no
 * automatic takeover.
 *
 * [send] is called only on a change for a computer (a computer that just linked counts as "idle" until told).
 */
class PeerCallRelay(private val send: (mac: String, active: Boolean) -> Unit) {
    private val sent = HashMap<String, Boolean>()

    /** [linkedComputers]: computers linked now. [inCall]: peers (any kind) that hold the AirPods in a call. */
    @Synchronized
    fun update(linkedComputers: Set<String>, inCall: Set<String>) {
        val computers = linkedComputers.mapTo(HashSet()) { it.uppercase() }
        val calls = inCall.mapTo(HashSet()) { it.uppercase() }
        sent.keys.retainAll(computers)
        for (computer in computers.sorted()) {
            val active = calls.any { it != computer }  // never tell a computer about its own call
            if ((sent[computer] ?: false) == active) continue
            sent[computer] = active
            send(computer, active)
        }
    }

    /** [mac]'s link went down: it forgets what it was told, so a relink starts from "idle". */
    @Synchronized
    fun linkDown(mac: String) {
        sent.remove(mac.uppercase())
    }

    companion object {
        /**
         * Peers whose fresh v2 snapshot says they hold the AirPods ([localHeadsetId], when known) and are in a
         * call. [freshAudioUses] carries only fresh observations (PeerOwnershipStore.freshAudioUses).
         */
        fun peersInCall(
            observations: Map<String, PeerObservation>,
            freshAudioUses: Map<String, PeerAudioUse>,
            localHeadsetId: String?,
        ): Set<String> = freshAudioUses.filter { (peer, use) ->
            val observation = observations[peer] ?: return@filter false
            use == PeerAudioUse.CALL && observation.ownership == PeerOwnership.HELD &&
                (localHeadsetId == null || observation.headsetId == localHeadsetId)
        }.keys.mapTo(HashSet()) { it.uppercase() }
    }
}
