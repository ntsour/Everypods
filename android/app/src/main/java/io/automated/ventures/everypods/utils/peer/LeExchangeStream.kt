/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * A virtual byte stream pair for one case-evidence exchange over the LE link ([PeerGattRecords.CASE] records),
 * so [RemoteCaseEvidenceExchange] and [RemoteCaseEvidenceMessage] run unchanged. [role] is 0 for the phone that
 * opened the exchange and 1 for the responder. Each `flush()` of [output] sends the buffered bytes through
 * [send] as one record; [deliver] feeds the peer's records to [input]. [close] unblocks a pending read with EOF.
 */
class LeExchangeStream(
    val mac: String,
    val role: Int,
    val id: Int,
    private val send: (role: Int, id: Int, bytes: ByteArray) -> Boolean,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closedFlag = false
    val isClosed: Boolean get() = closedFlag

    /** Hands the bytes of one received record to the reader. */
    fun deliver(bytes: ByteArray) {
        if (!closedFlag) queue.add(bytes)
    }

    fun close() {
        if (closedFlag) return
        closedFlag = true
        queue.add(EOF)
    }

    val input: InputStream = object : InputStream() {
        private var current: ByteArray = ByteArray(0)
        private var position = 0

        override fun read(): Int {
            if (!fill()) return -1
            return current[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - position)
            current.copyInto(b, off, position, position + n)
            position += n
            return n
        }

        private fun fill(): Boolean {
            while (position >= current.size) {
                if (closedFlag && queue.isEmpty()) return false
                val next = try { queue.take() } catch (_: InterruptedException) { throw IOException("interrupted") }
                if (next === EOF) { queue.add(EOF); return false }
                current = next
                position = 0
            }
            return true
        }
    }

    val output: OutputStream = object : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) { buffer.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) { buffer.write(b, off, len) }

        override fun flush() {
            if (buffer.size() == 0) return
            val bytes = buffer.toByteArray()
            buffer.reset()
            if (closedFlag || !send(role, id, bytes)) throw IOException("le_exchange_unavailable")
        }
    }

    private companion object { val EOF = ByteArray(0) }
}
