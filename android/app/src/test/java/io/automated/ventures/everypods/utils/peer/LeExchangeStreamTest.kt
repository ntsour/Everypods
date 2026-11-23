package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LeExchangeStreamTest {
    @Test fun `flush sends the buffered bytes as one record`() {
        val sent = mutableListOf<Triple<Int, Int, ByteArray>>()
        val stream = LeExchangeStream("M", 1, 42) { role, id, bytes -> sent += Triple(role, id, bytes); true }
        stream.output.write(byteArrayOf(1, 2))
        stream.output.write(3)
        assertTrue(sent.isEmpty())
        stream.output.flush()
        assertEquals(1, sent.size)
        assertEquals(1, sent[0].first)
        assertEquals(42, sent[0].second)
        assertArrayEquals(byteArrayOf(1, 2, 3), sent[0].third)
        stream.output.flush()
        assertEquals(1, sent.size)
    }

    @Test fun `delivered records are read in order across record boundaries`() {
        val stream = LeExchangeStream("M", 0, 1) { _, _, _ -> true }
        stream.deliver(byteArrayOf(1, 2))
        stream.deliver(byteArrayOf(3))
        val buffer = ByteArray(3)
        var read = 0
        while (read < 3) read += stream.input.read(buffer, read, 3 - read)
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer)
    }

    @Test fun `close unblocks a pending read with EOF and fails later flushes`() {
        val stream = LeExchangeStream("M", 0, 1) { _, _, _ -> true }
        val result = AtomicInteger(99)
        val done = CountDownLatch(1)
        Thread { result.set(stream.input.read()); done.countDown() }.start()
        Thread.sleep(50)
        stream.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(-1, result.get())
        assertEquals(-1, stream.input.read())
        stream.output.write(1)
        try { stream.output.flush(); fail() } catch (_: IOException) { }
    }

    @Test fun `a failed send surfaces as an IOException`() {
        val stream = LeExchangeStream("M", 0, 1) { _, _, _ -> false }
        stream.output.write(1)
        try { stream.output.flush(); fail() } catch (_: IOException) { }
        assertFalse(stream.isClosed)
    }

    @Test fun `the case exchange runs unchanged over two streams`() {
        val observer = java.util.UUID.randomUUID(); val owner = java.util.UUID.randomUUID()
        val headset = java.util.UUID.randomUUID(); val event = java.util.UUID.randomUUID()
        lateinit var opener: LeExchangeStream
        lateinit var responder: LeExchangeStream
        opener = LeExchangeStream("M", 0, 5) { _, _, b -> responder.deliver(b); true }
        responder = LeExchangeStream("M", 1, 5) { _, _, b -> opener.deliver(b); true }
        val observation = RemoteCaseEvidenceExchange.Observation(observer, headset, event, 100, true, true)
        val state = RemoteCaseEvidenceExchange.ReceiverState(owner, observer, headset, 7, true, true, true)
        val accepted = java.util.concurrent.atomic.AtomicReference<RemoteCaseEvidenceExchange.Accepted?>()
        val receiver = Thread {
            accepted.set(RemoteCaseEvidenceExchange.receive(responder.input, responder.output, "peer",
                { state }, { System.nanoTime() / 1_000_000 }, java.util.UUID::randomUUID, RemoteCaseEvidenceGate()))
            responder.close()
        }
        receiver.start()
        val sent = RemoteCaseEvidenceExchange.send(opener.input, opener.output, observation, owner,
            { owner }, { observation })
        receiver.join(2_000)
        assertTrue(sent)
        assertEquals(event, accepted.get()?.observation)
    }
}
