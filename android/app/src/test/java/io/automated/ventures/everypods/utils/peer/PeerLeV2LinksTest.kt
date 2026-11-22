package io.automated.ventures.everypods.utils.peer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PeerLeV2LinksTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val created = AtomicInteger()
    private val gone = AtomicInteger()
    private val mac = "AA:BB:CC:DD:EE:FF"

    private class FailingHooks(val onEnded: (String) -> Unit) : PeerLeV2Session.Hooks {
        override fun send(frame: PeerV2Frame.Frame) = false
        override fun isAuthorized() = true
        override fun localInstallationId() = "00000000-0000-4000-8000-00000000000a"
        override fun localSessionId() = "00000000-0000-4000-8000-0000000000a1"
        override fun localSnapshot(): Map<String, Any?> = emptyMap()
        override fun newConnectionId() = "00000000-0000-4000-8000-0000000000c1"
        override fun bindPeerIdentity(installationId: String) = Unit
        override fun observeSnapshot(remoteSessionId: String, snapshot: Map<String, Any?>) = true
        override fun markReady(remoteSessionId: String, acquisitionCapable: Boolean) = Unit
        override fun handleAcquisition(remoteSessionId: String, type: PeerV2Frame.Type, fields: Map<String, Any?>) = Unit
        override fun onEnded(reason: String) = onEnded.invoke(reason)
    }

    private fun links(retryMs: Long) = PeerLeV2Links(scheduler, { _, central, ended ->
        created.incrementAndGet()
        PeerLeV2Session(central, FailingHooks(ended)) { System.nanoTime() / 1_000_000 }
    }, { gone.incrementAndGet() }, retryDelayMs = retryMs, tickMs = 50)

    @After fun tearDown() { scheduler.shutdownNow() }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    @Test fun `a failed central handshake clears state and retries after the delay while the link stays up`() {
        val links = links(200)
        links.linkUp(mac, central = true)
        await { gone.get() >= 1 }
        assertEquals(1, created.get())
        await { created.get() >= 2 }
        assertFalse(links.isReady(mac))
        links.linkDown(mac, central = true)
    }

    @Test fun `link down stops retries and reports the state gone once`() {
        val links = links(300)
        links.linkUp(mac, central = true)
        await { gone.get() >= 1 }
        assertTrue(links.linkDown(mac, central = true))
        val count = created.get()
        Thread.sleep(500)
        assertEquals(count, created.get())
        assertFalse(links.linkDown(mac, central = true))
    }

    @Test fun `a link down for the other side leaves the registered link alone`() {
        val links = links(10_000)
        links.linkUp(mac, central = false)
        assertFalse(links.linkDown(mac, central = true))
        assertTrue(links.linkDown(mac, central = false))
    }

    @Test fun `a peripheral restarts a fresh session at once when one fails`() {
        val links = links(10_000)
        links.linkUp(mac, central = false)
        links.onRecord(mac, byteArrayOf(1, 2, 3))   // not a V2 frame: the session fails
        await { created.get() >= 2 }
        links.linkDown(mac)
    }
}
