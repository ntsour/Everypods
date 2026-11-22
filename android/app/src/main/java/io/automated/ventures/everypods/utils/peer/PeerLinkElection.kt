/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

/**
 * Which phone is the Bluetooth LE central for a phone-to-phone link (docs/rfcomm-removal-plan.md, D1).
 *
 * The phone with the smaller installation ID is the central. A phone only learns the other's ID from its
 * HELLO, so on first contact both phones may dial: each then keeps its central link only if its own ID is
 * smaller, and the other side closes its own. Once the peer's ID is remembered, only the elected phone dials.
 * Replaces the election by Bluetooth name for LE, which elected nobody when two phones share a name.
 */
internal object PeerLinkElection {
    /** Whether this phone should dial [peerId]'s phone. Unknown ID: dial (first contact, deduped at HELLO). */
    fun shouldDial(ownId: String, peerId: String?): Boolean = peerId == null || isCentral(ownId, peerId)

    /** After the peer's HELLO arrives over our central link: keep the link only if we are the elected central. */
    fun keepCentralLink(ownId: String, peerId: String): Boolean = isCentral(ownId, peerId)

    private fun isCentral(ownId: String, peerId: String): Boolean = ownId.lowercase() < peerId.lowercase()
}
