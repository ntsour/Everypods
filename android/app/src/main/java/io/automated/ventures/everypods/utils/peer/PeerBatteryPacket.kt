package io.automated.ventures.everypods.utils.peer

/**
 * Battery and wear state read from the AirPods' BLE status advert, shared with a peer phone.
 *
 * Some phones never receive that advert (HyperOS keeps it for its own AirPods helper) and also
 * lose the control channel to that helper, so they hold the audio with no battery at all. A peer
 * that does see the advert forwards it over the legacy link, which older builds ignore safely.
 *
 * Wire format (8 bytes): HEADER, left, right, case (0..100, 0xFF = unknown), flags.
 */
object PeerBatteryPacket {
    val HEADER = byteArrayOf(0x00, 0x05, 0x00, 0x01)
    const val SIZE = 8
    private const val UNKNOWN = 0xFF

    data class Reading(
        val left: Int?, val right: Int?, val case: Int?,
        val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean,
        val leftInEar: Boolean, val rightInEar: Boolean,
    )

    fun encode(r: Reading): ByteArray {
        var flags = 0
        if (r.leftCharging) flags = flags or 0x01
        if (r.rightCharging) flags = flags or 0x02
        if (r.caseCharging) flags = flags or 0x04
        if (r.leftInEar) flags = flags or 0x08
        if (r.rightInEar) flags = flags or 0x10
        return HEADER + byteArrayOf(level(r.left), level(r.right), level(r.case), flags.toByte())
    }

    fun isPacket(raw: ByteArray): Boolean =
        raw.size == SIZE && raw.copyOfRange(0, HEADER.size).contentEquals(HEADER)

    fun decode(raw: ByteArray): Reading? {
        if (!isPacket(raw)) return null
        val flags = raw[7].toInt() and 0xFF
        if (flags and 0xE0 != 0) return null
        val left = parse(raw[4]) ?: return null
        val right = parse(raw[5]) ?: return null
        val case = parse(raw[6]) ?: return null
        return Reading(
            left.value, right.value, case.value,
            flags and 0x01 != 0, flags and 0x02 != 0, flags and 0x04 != 0,
            flags and 0x08 != 0, flags and 0x10 != 0,
        )
    }

    private fun level(v: Int?): Byte = (v?.takeIf { it in 0..100 } ?: UNKNOWN).toByte()

    private class Level(val value: Int?)
    /** null = malformed; Level(null) = unknown. */
    private fun parse(b: Byte): Level? {
        val v = b.toInt() and 0xFF
        return when {
            v == UNKNOWN -> Level(null)
            v in 0..100 -> Level(v)
            else -> null
        }
    }
}
