package io.automated.ventures.everypods.bluetooth.connection

interface ConnectionEventSink {
    fun emit(event: ConnectionEvent)
}
