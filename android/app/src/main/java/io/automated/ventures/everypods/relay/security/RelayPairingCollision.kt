/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

/** Both peers select the challenge from the smaller identity when setup starts simultaneously. */
internal object RelayPairingCollision {
    fun keepLocalChallenge(localIdentity: ByteArray, remoteIdentity: ByteArray): Boolean {
        for (index in 0 until minOf(localIdentity.size, remoteIdentity.size)) {
            val comparison = (localIdentity[index].toInt() and 0xff) - (remoteIdentity[index].toInt() and 0xff)
            if (comparison != 0) return comparison < 0
        }
        require(localIdentity.size != remoteIdentity.size) { "Cannot pair a device with itself" }
        return localIdentity.size < remoteIdentity.size
    }
}
