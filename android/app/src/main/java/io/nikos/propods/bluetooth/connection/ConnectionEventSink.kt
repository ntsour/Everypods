package io.nikos.propods.bluetooth.connection

interface ConnectionEventSink {
    fun emit(event: ConnectionEvent)
}
