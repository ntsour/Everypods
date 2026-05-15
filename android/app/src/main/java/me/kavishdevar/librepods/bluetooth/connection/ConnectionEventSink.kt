package me.kavishdevar.librepods.bluetooth.connection

interface ConnectionEventSink {
    fun emit(event: ConnectionEvent)
}
