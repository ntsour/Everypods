package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RemoteCaseLeTransportTest {
    private val addrObserver = "00:11:22:33:44:01"
    private val addrOwner = "00:11:22:33:44:02"
    private val observerSession = UUID.randomUUID()
    private val ownerSession = UUID.randomUUID()
    private val headset = UUID.randomUUID()

    private class Link(var target: () -> RemoteCaseBluetoothTransport?, val from: String, var ready: Boolean = true) :
        RemoteCaseBluetoothTransport.LeLink {
        val records = mutableListOf<ByteArray>()
        override fun isReady(address: String) = ready
        override fun send(address: String, payload: ByteArray): Boolean {
            records += payload
            target()?.onLeRecord(from, payload)
            return true
        }
    }

    private class ObserverHooks(val peer: String, val session: UUID, val obs: RemoteCaseEvidenceExchange.Observation) :
        RemoteCaseBluetoothTransport.Hooks {
        override fun peers() = setOf(peer)
        override fun peerSession(address: String): UUID? = session
        override fun currentObservation(id: UUID) = obs
        override fun receiverState(address: String): RemoteCaseEvidenceExchange.ReceiverState? = null
        override fun onAccepted(evidence: RemoteCaseEvidenceExchange.Accepted) = Unit
        override fun onWithdrawal(address: String, message: RemoteCaseEvidenceMessage.Message.Withdrawal) = Unit
    }

    private class OwnerHooks(val peer: String, val remote: UUID, val local: UUID, val headset: UUID) :
        RemoteCaseBluetoothTransport.Hooks {
        val accepted = AtomicReference<RemoteCaseEvidenceExchange.Accepted?>()
        val withdrawn = AtomicReference<RemoteCaseEvidenceMessage.Message.Withdrawal?>()
        val done = CountDownLatch(1)
        override fun peers() = setOf(peer)
        override fun peerSession(address: String): UUID? = remote
        override fun currentObservation(id: UUID): RemoteCaseEvidenceExchange.Observation? = null
        override fun receiverState(address: String) =
            RemoteCaseEvidenceExchange.ReceiverState(local, remote, headset, 3, true, true, true)
        override fun onAccepted(evidence: RemoteCaseEvidenceExchange.Accepted) { accepted.set(evidence); done.countDown() }
        override fun onWithdrawal(address: String, message: RemoteCaseEvidenceMessage.Message.Withdrawal) { withdrawn.set(message); done.countDown() }
    }

    private fun pair(): Triple<RemoteCaseBluetoothTransport, RemoteCaseBluetoothTransport, OwnerHooks> {
        val observation = RemoteCaseEvidenceExchange.Observation(observerSession, headset, UUID.randomUUID(), 100, true, true)
        lateinit var owner: RemoteCaseBluetoothTransport
        lateinit var observer: RemoteCaseBluetoothTransport
        val ownerHooks = OwnerHooks(addrObserver, observerSession, ownerSession, headset)
        val toOwner = Link({ owner }, addrObserver)
        val toObserver = Link({ observer }, addrOwner)
        observer = RemoteCaseBluetoothTransport(ObserverHooks(addrOwner, ownerSession, observation), le = toOwner)
        owner = RemoteCaseBluetoothTransport(ownerHooks, le = toObserver)
        lastObservation = observation
        return Triple(observer, owner, ownerHooks)
    }

    private lateinit var lastObservation: RemoteCaseEvidenceExchange.Observation

    @Test fun `observation is confirmed over the LE link`() {
        val (observer, owner, hooks) = pair()
        try {
            observer.publish(lastObservation)
            assertTrue(hooks.done.await(3, TimeUnit.SECONDS))
            assertEquals(lastObservation.id, hooks.accepted.get()?.observation)
        } finally { observer.close(); owner.close() }
    }

    @Test fun `withdrawal is delivered over the LE link`() {
        val (observer, owner, hooks) = pair()
        try {
            val message = RemoteCaseEvidenceMessage.Message.Withdrawal(observerSession, headset, UUID.randomUUID())
            observer.publishWithdrawal(message)
            assertTrue(hooks.done.await(3, TimeUnit.SECONDS))
            assertEquals(message, hooks.withdrawn.get())
        } finally { observer.close(); owner.close() }
    }

    @Test fun `a responder record for an unknown exchange and a short record are dropped`() {
        val (observer, owner, hooks) = pair()
        try {
            val hint = RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Hint(observerSession, headset, UUID.randomUUID()))
            owner.onLeRecord(addrObserver, byteArrayOf(1, 0, 9) + hint)
            owner.onLeRecord(addrObserver, byteArrayOf(0, 0))
            assertTrue(!hooks.done.await(300, TimeUnit.MILLISECONDS))
        } finally { observer.close(); owner.close() }
    }
}
