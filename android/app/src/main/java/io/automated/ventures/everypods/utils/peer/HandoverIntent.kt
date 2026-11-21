package io.automated.ventures.everypods.utils.peer

/** Why a peer is asking for the headset; carried by ACQUIRE_REQUEST between acquire-v2 peers only. */
enum class HandoverIntent(val wire: String) {
    CALL("call"), MANUAL("manual"), MEDIA("media"), PASSIVE("passive");

    companion object {
        fun fromWire(value: String?): HandoverIntent? = entries.firstOrNull { it.wire == value }
    }
}
