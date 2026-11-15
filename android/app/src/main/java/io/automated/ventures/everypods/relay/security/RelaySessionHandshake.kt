/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import java.security.MessageDigest

/** Binds fresh random contributions from both roles to the session key. */
internal object RelaySessionHandshake {
    fun sessionNonce(initiatorNonce: ByteArray, responderNonce: ByteArray): ByteArray {
        require(initiatorNonce.size == 32 && responderNonce.size == 32)
        return MessageDigest.getInstance("SHA-256").digest(
            "EveryPods relay session v2".toByteArray(Charsets.UTF_8) + initiatorNonce + responderNonce,
        )
    }
}
