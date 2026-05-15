package io.nikos.andropods.bluetooth.connection

interface ConnectionEventSink {
    fun emit(event: ConnectionEvent)
}
