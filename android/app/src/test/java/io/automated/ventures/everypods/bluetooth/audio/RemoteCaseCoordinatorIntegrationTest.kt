package io.automated.ventures.everypods.bluetooth.audio

import io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceExchange
import io.automated.ventures.everypods.utils.peer.RemoteCaseEvidenceGate
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the production exchange and coordinator without pretending to test Bluetooth. */
class RemoteCaseCoordinatorIntegrationTest {
    @Test fun `peer observation requests coordination and expired evidence cannot dispatch`() {
        val observer = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val headset = UUID.randomUUID()
        val observation = UUID.randomUUID()
        val ownerInput = PipedInputStream()
        val observerOutput = PipedOutputStream(ownerInput)
        val observerInput = PipedInputStream()
        val ownerOutput = PipedOutputStream(observerInput)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val receiver = pool.submit<RemoteCaseEvidenceExchange.Accepted?> {
                RemoteCaseEvidenceExchange.receive(ownerInput, ownerOutput, "peer",
                    { RemoteCaseEvidenceExchange.ReceiverState(owner, observer, headset, 1, true, true, true) },
                    { 1_000L }, UUID::randomUUID, RemoteCaseEvidenceGate())
            }
            val evidence = RemoteCaseEvidenceExchange.Observation(observer, headset, observation, 300, true, true)
            assertTrue(RemoteCaseEvidenceExchange.send(observerInput, observerOutput, evidence, owner,
                { owner }, { evidence }))
            val accepted = receiver.get(3, TimeUnit.SECONDS)!!
            val coordinator = AudioConnectionCoordinator()
            val intent = AudioConnectionCoordinator.Intent(1, "headset", AudioConnectionCoordinator.Trigger.PASSIVE, 10_000, 1)
            val snapshot = AudioConnectionCoordinator.Evidence(1_000, enabled = true, fresh = accepted.remainingFreshMs > 0,
                budsStored = false, adapterAvailable = true, peerBusy = false, requiresGrant = true,
                grantValid = false, profile = AudioConnectionCoordinator.Profile.DISCONNECTED)
            assertTrue(coordinator.submit(intent, snapshot).single() is AudioConnectionCoordinator.Effect.Coordinate)
            val expired = coordinator.assess(snapshot.copy(nowMs = 6_000, fresh = false,
                grantValid = true, grantIntentId = 1, grantCommandNumber = 1))
            assertEquals(AudioConnectionCoordinator.Result.STALE_INTENT,
                (expired.single() as AudioConnectionCoordinator.Effect.Outcome).result)
        } finally {
            ownerInput.close(); observerInput.close(); ownerOutput.close(); observerOutput.close()
            pool.shutdownNow()
        }
    }
}
