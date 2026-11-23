package io.automated.ventures.everypods.utils.peer

/**
 * Outgoing byte stream for one GATT central: records are chunked to the current
 * `MTU − 3` and handed out one notification at a time (Android allows only one
 * `notifyCharacteristicChanged` in flight per device; the next goes out from
 * `onNotificationSent`). Not thread-safe: owned by the GATT server's thread.
 */
class PeerGattOutbox(private val maxQueuedBytes: Int = 64 * 1024) {
    private val chunks = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    var mtu: Int = 23
        set(value) { field = value.coerceIn(23, 517) }
    var inFlightSinceMs: Long? = null
        private set

    val isEmpty: Boolean get() = chunks.isEmpty()
    val inFlight: Boolean get() = inFlightSinceMs != null

    /** Queues a whole record; false (nothing queued) if it would exceed the queue limit. */
    fun enqueue(record: ByteArray): Boolean {
        if (queuedBytes + record.size > maxQueuedBytes) return false
        PeerGattRecords.chunk(record, chunkSize(mtu)).forEach { chunks.addLast(it) }
        queuedBytes += record.size
        return true
    }

    /** The next chunk to notify, or null if one is already in flight or nothing is queued. */
    fun next(nowMs: Long): ByteArray? {
        if (inFlight || chunks.isEmpty()) return null
        inFlightSinceMs = nowMs
        return chunks.first()
    }

    /** The chunk returned by [next] was sent (or must be dropped). */
    fun onSent() {
        if (inFlightSinceMs == null) return
        inFlightSinceMs = null
        chunks.removeFirstOrNull()?.let { queuedBytes -= it.size }
    }

    /** The notify call was refused: keep the chunk at the head for a retry. */
    fun onRefused() {
        inFlightSinceMs = null
    }

    /** Clears an in-flight chunk whose `onNotificationSent` never came; true if it did. */
    fun expireInFlight(nowMs: Long, timeoutMs: Long): Boolean {
        val since = inFlightSinceMs ?: return false
        if (nowMs - since < timeoutMs) return false
        onSent()
        return true
    }

    fun clear() {
        chunks.clear()
        queuedBytes = 0
        inFlightSinceMs = null
    }

    companion object {
        /** An attribute value is at most 512 bytes (Core spec Vol 3 Part F 3.2.9); Android refuses longer writes. */
        const val MAX_ATTRIBUTE_VALUE = 512

        /** Bytes per write or notification: the ATT payload (MTU − 3), capped at [MAX_ATTRIBUTE_VALUE]. */
        fun chunkSize(mtu: Int): Int = minOf(mtu - 3, MAX_ATTRIBUTE_VALUE)
    }
}
