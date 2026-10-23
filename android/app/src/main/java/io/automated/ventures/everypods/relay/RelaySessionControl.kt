/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Commands carried in the reliable DTLS/SCTP control channel. */
object RelaySessionControl {
    const val RECEIVER_READY: Byte = 1
    const val RECEIVER_UNAVAILABLE: Byte = 2
    const val CAPTURE_STARTED: Byte = 3
    const val SOURCE_AUDIO_ACTIVITY: Byte = 4
    const val STOP: Byte = 5
    const val STOP_ACK: Byte = 6
    const val AUDIO_STARTED: Byte = 7
    const val HEART_RATE_SAMPLE: Byte = 8
    const val HEART_RATE_UNAVAILABLE: Byte = 9
    const val HEART_RATE_SHARING: Byte = 10
    const val PLAYBACK_BUFFERING: Byte = 11
    const val CLOCK_SYNC_REQUEST: Byte = 12
    const val CLOCK_SYNC_RESPONSE: Byte = 13

    const val PLAYBACK_INTERRUPTION: Byte = 14
    const val RESUME_REQUEST: Byte = 15
    const val SUSPENSION_EXPIRED: Byte = 16

    fun interruption(reason: RelayInterruption, resumeUnavailable: Boolean): ByteArray = byteArrayOf(
        PLAYBACK_INTERRUPTION,
        when (reason) { RelayInterruption.NONE -> 0; RelayInterruption.TEMPORARY -> 1; RelayInterruption.LOCAL_AUDIO -> 2 },
        if (resumeUnavailable) 1 else 0,
    )

    fun decodeInterruption(packet: ByteArray): Pair<RelayInterruption, Boolean> {
        require(packet.size == 3 && packet[0] == PLAYBACK_INTERRUPTION && packet[2].toInt() in 0..1)
        val reason = when (packet[1].toInt()) {
            0 -> RelayInterruption.NONE
            1 -> RelayInterruption.TEMPORARY
            2 -> RelayInterruption.LOCAL_AUDIO
            else -> error("Invalid relay interruption")
        }
        require(reason != RelayInterruption.NONE || packet[2].toInt() == 0)
        return reason to (packet[2].toInt() == 1)
    }

    fun command(code: Byte): ByteArray = byteArrayOf(code)

    fun reason(code: Byte, message: String): ByteArray {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_REASON_BYTES)
        return byteArrayOf(code, bytes.size.toByte(), *bytes)
    }

    fun activity(active: Boolean): ByteArray = byteArrayOf(SOURCE_AUDIO_ACTIVITY, if (active) 1 else 0)
    fun buffering(active: Boolean): ByteArray = byteArrayOf(PLAYBACK_BUFFERING, if (active) 1 else 0)
    fun heartRate(bpm: Int): ByteArray { require(bpm in 1..255); return byteArrayOf(HEART_RATE_SAMPLE, bpm.toByte()) }
    fun heartRateSharing(active: Boolean, bluetooth: Boolean, tickr: Boolean): ByteArray = byteArrayOf(
        HEART_RATE_SHARING,
        if (active) 1 else 0,
        if (bluetooth) 1 else 0,
        if (tickr) 1 else 0,
    )
    fun clockSyncRequest(id: Long, receiverSendNanos: Long): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        .put(CLOCK_SYNC_REQUEST).putLong(id).putLong(receiverSendNanos).array()
    fun clockSyncResponse(id: Long, receiverSendNanos: Long, sourceReceiveNanos: Long, sourceSendNanos: Long): ByteArray =
        ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN).put(CLOCK_SYNC_RESPONSE).putLong(id)
            .putLong(receiverSendNanos).putLong(sourceReceiveNanos).putLong(sourceSendNanos).array()

    fun decodeReason(packet: ByteArray): String {
        require(packet.size >= 2 && packet.size <= MAX_REASON_BYTES + 2)
        val size = packet[1].toInt() and 0xff
        require(size == packet.size - 2)
        return packet.copyOfRange(2, packet.size).toString(Charsets.UTF_8)
    }

    private const val MAX_REASON_BYTES = 240
}
