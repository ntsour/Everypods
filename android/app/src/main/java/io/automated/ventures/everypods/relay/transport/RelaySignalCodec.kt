/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import io.automated.ventures.everypods.relay.RelayAudioTuning
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Bounded binary representation of authenticated SDP and trickled ICE signaling. */
object RelaySignalCodec {
    private const val VERSION = 1
    private const val SDP = 1
    private const val ICE = 2
    private const val REQUEST_PATH = 3
    private const val OFFER_PATH = 4
    private const val CONFIRM_PATH = 5
    private const val PATH_UNAVAILABLE = 6
    private const val SWITCH_PENDING = 7
    private const val RECEIVER_BUSY = 8
    private const val CANCEL_REQUEST = 9
    private const val PEER_SESSION_STATUS = 10
    private const val REVOKE_RELAY = 11
    private const val REVOKE_RELAY_ACK = 12
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val NULL_MID = 0xffff

    fun encode(signal: WebRtcRelayTransport.Signal): ByteArray = when (signal) {
        is WebRtcRelayTransport.Signal.RequestPath -> ByteBuffer.allocate(3 + RelayAudioTuning.WIRE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte()).put(REQUEST_PATH.toByte()).put(when (signal.mode) { "auto" -> 0; "wifi" -> 1; else -> 2 }.toByte())
            .put(signal.tuning.encodeWire()).array()
        is WebRtcRelayTransport.Signal.OfferPath -> {
            require(signal.prefix in 1..30)
            ByteBuffer.allocate(7 + RelayAudioTuning.WIRE_BYTES).order(ByteOrder.BIG_ENDIAN).put(VERSION.toByte()).put(OFFER_PATH.toByte())
                .put(signal.prefix.toByte()).putInt(RelayNetworkPath.ipv4(signal.address).toInt()).put(signal.tuning.encodeWire()).array()
        }
        is WebRtcRelayTransport.Signal.ConfirmPath -> ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte()).put(CONFIRM_PATH.toByte()).putInt(RelayNetworkPath.ipv4(signal.address).toInt()).array()
        WebRtcRelayTransport.Signal.PathUnavailable -> byteArrayOf(VERSION.toByte(), PATH_UNAVAILABLE.toByte())
        WebRtcRelayTransport.Signal.SwitchPending -> byteArrayOf(VERSION.toByte(), SWITCH_PENDING.toByte())
        WebRtcRelayTransport.Signal.ReceiverBusy -> byteArrayOf(VERSION.toByte(), RECEIVER_BUSY.toByte())
        WebRtcRelayTransport.Signal.RevokeRelay -> byteArrayOf(VERSION.toByte(), REVOKE_RELAY.toByte())
        WebRtcRelayTransport.Signal.RevokeRelayAck -> byteArrayOf(VERSION.toByte(), REVOKE_RELAY_ACK.toByte())
        WebRtcRelayTransport.Signal.CancelRequest -> byteArrayOf(VERSION.toByte(), CANCEL_REQUEST.toByte())
        is WebRtcRelayTransport.Signal.PeerSessionStatus -> {
            val name = signal.senderName.toByteArray(StandardCharsets.UTF_8)
            require(name.size <= 128)
            ByteBuffer.allocate(5 + name.size).order(ByteOrder.BIG_ENDIAN)
                .put(VERSION.toByte()).put(PEER_SESSION_STATUS.toByte()).put(if (signal.active) 1 else 0)
                .putShort(name.size.toShort()).put(name).array()
        }
        is WebRtcRelayTransport.Signal.Description -> {
            val sdp = signal.sdp.toByteArray(StandardCharsets.UTF_8)
            require(sdp.size in 1..MAX_TEXT_BYTES) { "SDP size is outside the supported limit" }
            val type = when (signal.type) {
                SessionDescription.Type.OFFER -> 1
                SessionDescription.Type.ANSWER -> 2
                else -> error("Unsupported WebRTC description type")
            }
            ByteBuffer.allocate(1 + 1 + 1 + 4 + sdp.size).order(ByteOrder.BIG_ENDIAN)
                .put(VERSION.toByte()).put(SDP.toByte()).put(type.toByte()).putInt(sdp.size).put(sdp).array()
        }
        is WebRtcRelayTransport.Signal.Candidate -> {
            val mid = signal.mid?.toByteArray(StandardCharsets.UTF_8)
            require(mid == null || mid.size < NULL_MID) { "ICE media identifier is too large" }
            val candidate = signal.value.toByteArray(StandardCharsets.UTF_8)
            require(candidate.size in 1..MAX_TEXT_BYTES) { "ICE candidate size is outside the supported limit" }
            ByteBuffer.allocate(1 + 1 + 2 + (mid?.size ?: 0) + 4 + 4 + candidate.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(VERSION.toByte()).put(ICE.toByte())
                .putShort((mid?.size ?: NULL_MID).toShort()).apply { if (mid != null) put(mid) }
                .putInt(signal.lineIndex).putInt(candidate.size).put(candidate).array()
        }
    }

    fun decode(encoded: ByteArray): WebRtcRelayTransport.Signal {
        require(encoded.size in 2..(MAX_TEXT_BYTES + 512)) { "Invalid WebRTC signaling message size" }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported WebRTC signaling version" }
        return when (input.get().toInt() and 0xff) {
            REQUEST_PATH -> {
                require(input.remaining() == 1 || input.remaining() == 1 + RelayAudioTuning.WIRE_BYTES)
                val mode = input.get().toInt()
                require(mode in 0..2)
                val tuning = if (input.hasRemaining()) RelayAudioTuning.decodeWire(ByteArray(input.remaining()).also(input::get)) else RelayAudioTuning.DEFAULT
                WebRtcRelayTransport.Signal.RequestPath(when (mode) { 0 -> "auto"; 1 -> "wifi"; else -> "hotspot" }, tuning)
            }
            OFFER_PATH -> {
                require(input.remaining() == 5 || input.remaining() == 5 + RelayAudioTuning.WIRE_BYTES)
                val prefix = input.get().toInt()
                require(prefix in 1..30)
                val address = readAddress(input)
                val tuning = if (input.hasRemaining()) RelayAudioTuning.decodeWire(ByteArray(input.remaining()).also(input::get)) else RelayAudioTuning.DEFAULT
                WebRtcRelayTransport.Signal.OfferPath(address, prefix, tuning)
            }
            CONFIRM_PATH -> {
                require(input.remaining() == 4)
                WebRtcRelayTransport.Signal.ConfirmPath(readAddress(input))
            }
            PATH_UNAVAILABLE -> {
                require(!input.hasRemaining())
                WebRtcRelayTransport.Signal.PathUnavailable
            }
            SWITCH_PENDING -> {
                require(!input.hasRemaining())
                WebRtcRelayTransport.Signal.SwitchPending
            }
            RECEIVER_BUSY -> {
                require(!input.hasRemaining())
                WebRtcRelayTransport.Signal.ReceiverBusy
            }
            REVOKE_RELAY -> { require(!input.hasRemaining()); WebRtcRelayTransport.Signal.RevokeRelay }
            REVOKE_RELAY_ACK -> { require(!input.hasRemaining()); WebRtcRelayTransport.Signal.RevokeRelayAck }
            CANCEL_REQUEST -> {
                require(!input.hasRemaining())
                WebRtcRelayTransport.Signal.CancelRequest
            }
            PEER_SESSION_STATUS -> {
                require(input.remaining() >= 3)
                val active = input.get().toInt()
                val size = input.short.toInt() and 0xffff
                require(active in 0..1 && size <= 128 && input.remaining() == size)
                val name = ByteArray(size).also(input::get).toString(StandardCharsets.UTF_8)
                WebRtcRelayTransport.Signal.PeerSessionStatus(active == 1, name)
            }
            SDP -> {
                require(input.remaining() >= 5) { "Truncated SDP header" }
                val type = when (input.get().toInt() and 0xff) {
                    1 -> SessionDescription.Type.OFFER
                    2 -> SessionDescription.Type.ANSWER
                    else -> error("Unknown SDP type")
                }
                val length = input.int
                require(length in 1..MAX_TEXT_BYTES && length == input.remaining()) { "Invalid SDP length" }
                val text = ByteArray(length).also(input::get).toString(StandardCharsets.UTF_8)
                WebRtcRelayTransport.Signal.Description(type, text)
            }
            ICE -> {
                require(input.remaining() >= 10) { "Truncated ICE header" }
                val midSize = input.short.toInt() and 0xffff
                val mid = if (midSize == NULL_MID) null else {
                    require(midSize <= 512 && midSize <= input.remaining() - 8) { "Invalid ICE media identifier length" }
                    ByteArray(midSize).also(input::get).toString(StandardCharsets.UTF_8)
                }
                val lineIndex = input.int
                val length = input.int
                require(lineIndex >= 0 && length in 1..MAX_TEXT_BYTES && length == input.remaining()) {
                    "Invalid ICE candidate length"
                }
                val value = ByteArray(length).also(input::get).toString(StandardCharsets.UTF_8)
                WebRtcRelayTransport.Signal.Candidate(mid, lineIndex, value)
            }
            else -> error("Unknown WebRTC signaling message")
        }
    }

    private fun readAddress(input: ByteBuffer): String {
        val address = input.int.toLong() and 0xffff_ffffL
        return listOf(24, 16, 8, 0).joinToString(".") { ((address shr it) and 255).toString() }
    }
}
