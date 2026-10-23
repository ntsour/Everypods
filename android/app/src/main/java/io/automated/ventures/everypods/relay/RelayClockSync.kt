package io.automated.ventures.everypods.relay

/** NTP-style offset estimate: remote clock minus the local receiver clock. */
data class RelayClockSyncSample(val remoteMinusLocalNanos: Long, val roundTripNanos: Long) {
    companion object {
        fun calculate(localSend: Long, remoteReceive: Long, remoteSend: Long, localReceive: Long): RelayClockSyncSample? {
            if (localSend < 0 || remoteReceive < 0 || remoteSend < 0 || localReceive < 0) return null
            if (localReceive < localSend || remoteSend < remoteReceive) return null
            val processing = remoteSend - remoteReceive
            val roundTrip = (localReceive - localSend) - processing
            if (roundTrip < 0) return null
            val offset = ((remoteReceive - localSend) + (remoteSend - localReceive)) / 2
            return RelayClockSyncSample(offset, roundTrip)
        }
    }
}
