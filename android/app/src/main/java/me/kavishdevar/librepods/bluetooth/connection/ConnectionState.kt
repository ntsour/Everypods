package me.kavishdevar.librepods.bluetooth.connection

sealed class ConnectionState {
    object Idle : ConnectionState()
    data class Connecting(val attemptId: Int, val attemptNumber: Int) : ConnectionState()
    data class Handshaking(val attemptId: Int) : ConnectionState()
    data class Connected(val attemptId: Int) : ConnectionState()
    object Disconnecting : ConnectionState()
    data class Failed(val reason: FailureReason) : ConnectionState()
}

sealed class FailureReason {
    object Timeout : FailureReason()
    data class IoError(val message: String) : FailureReason()
    object HandshakeTimeout : FailureReason()
    object BluetoothOff : FailureReason()
    object MaxRetriesExhausted : FailureReason()
    object Cancelled : FailureReason()
}

sealed class ConnectionEvent {
    data class L2capConnected(val attemptId: Int) : ConnectionEvent()
    data class Disconnected(val reason: String) : ConnectionEvent()
    data class Failed(val reason: FailureReason) : ConnectionEvent()
}

enum class ConnectResult { Connected, AlreadyConnecting, Failed }
