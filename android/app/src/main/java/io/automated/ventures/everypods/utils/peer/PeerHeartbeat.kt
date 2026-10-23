package io.automated.ventures.everypods.utils.peer

import java.util.UUID

sealed interface PeerHeartbeatAction {
    data class SendPing(val nonce: String) : PeerHeartbeatAction
    data object AwaitResponse : PeerHeartbeatAction
    data object TimedOut : PeerHeartbeatAction
}

/** Nonce-correlated peer heartbeat with two missed response intervals before teardown. */
class PeerHeartbeat(private val nonceFactory: () -> String = { UUID.randomUUID().toString() }) {
    private var outstandingNonce: String? = null
    private var missedIntervals = 0

    @Synchronized fun startPing(): String? {
        if (outstandingNonce != null) return null
        return nonceFactory().also { outstandingNonce = it }
    }

    @Synchronized fun onInterval(): PeerHeartbeatAction {
        if (outstandingNonce != null) {
            missedIntervals++
            return if (missedIntervals >= MAX_MISSED_INTERVALS) PeerHeartbeatAction.TimedOut
            else PeerHeartbeatAction.AwaitResponse
        }
        return PeerHeartbeatAction.SendPing(startPing()!!)
    }

    @Synchronized fun acknowledge(nonce: String): Boolean {
        if (nonce != outstandingNonce) return false
        outstandingNonce = null
        missedIntervals = 0
        return true
    }

    @Synchronized fun hasOutstandingPing(): Boolean = outstandingNonce != null

    companion object { const val MAX_MISSED_INTERVALS = 2 }
}
