package io.automated.ventures.everypods.bluetooth.audio

import io.automated.ventures.everypods.utils.peer.ElapsedClock
import io.automated.ventures.everypods.utils.peer.PassiveAcquisitionCoordinator
import io.automated.ventures.everypods.utils.peer.PeerAudioUse
import io.automated.ventures.everypods.utils.peer.PeerOwnership
import org.junit.Assert.*
import org.junit.Test

/** Uses the real peer reservation coordinator rather than an always-granting mock. */
class AudioPeerGrantIntegrationTest {
    @Test fun `binding commits only after peer start ack and cancellation cannot release issued command`() {
        val local = "00000000-0000-4000-8000-000000000001"
        val peer = "00000000-0000-4000-8000-000000000002"
        val headset = "00000000-0000-4000-8000-000000000099"
        val session = "00000000-0000-4000-8000-000000000012"
        val grant = "00000000-0000-4000-8000-000000000013"
        val peerStatus = PassiveAcquisitionCoordinator.PeerStatus(peer, session, 3, 2, true,
            headset, PeerOwnership.RELEASED, PeerAudioUse.IDLE)
        val statuses = mapOf(peer to peerStatus)
        val coordinator = PassiveAcquisitionCoordinator(local, ElapsedClock { 1_000L })
        val backend = object : AudioPeerGrantBridge.Backend {
            override fun begin(): AudioPeerGrantBridge.Request? {
                val event = coordinator.begin(headset, "local-session", setOf(local, peer),
                    true, true, true, true, 4, statuses) as? PassiveAcquisitionCoordinator.Event.Request ?: return null
                return AudioPeerGrantBridge.Request(event.requestId, event.headsetId)
            }
            override fun isCurrent(request: AudioPeerGrantBridge.Request): Boolean =
                coordinator.currentIntent()?.requestId == request.requestId
            override fun authorize(ready: PassiveAcquisitionCoordinator.Event.ReadyToConnect, issueCommand: Boolean): Boolean =
                coordinator.authorizeConnect(ready.requestId, ready.generation, true, true,
                    headset, setOf(local, peer), statuses, 4, issueCommand)
            override fun cancel(request: AudioPeerGrantBridge.Request) {
                coordinator.cancelMatching(request.requestId, request.headsetId)
            }
        }
        val bridge = AudioPeerGrantBridge(backend)
        assertTrue(bridge.begin(7, 1))
        val request = coordinator.currentIntent()!!
        assertFalse(bridge.commit(7, 1))
        assertEquals(1, coordinator.onGrant(peer, session, request.requestId, grant).size)
        val ready = coordinator.onStartAck(peer, session, request.requestId, grant) as PassiveAcquisitionCoordinator.Event.ReadyToConnect
        assertNotNull(bridge.ready(ready))
        assertTrue(bridge.isValid(7, 1))
        assertTrue(bridge.commit(7, 1))
        assertTrue(coordinator.currentIntent()!!.connectIssued)
        bridge.cancel(7)
        assertEquals(PassiveAcquisitionCoordinator.Phase.IN_PROGRESS, coordinator.currentIntent()!!.phase)
        assertTrue(coordinator.currentIntent()!!.connectIssued)
    }
}
