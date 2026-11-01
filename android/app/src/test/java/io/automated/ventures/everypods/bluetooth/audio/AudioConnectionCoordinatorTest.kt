package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

class AudioConnectionCoordinatorTest {
    private val headset = "aa:bb"
    private fun intent(id: Long = 1, expires: Long = 60_000) =
        AudioConnectionCoordinator.Intent(id, headset, AudioConnectionCoordinator.Trigger.PASSIVE, expires)
    private fun evidence(now: Long = 0, profile: AudioConnectionCoordinator.Profile = AudioConnectionCoordinator.Profile.DISCONNECTED) =
        AudioConnectionCoordinator.Evidence(now, enabled = true, fresh = true, budsStored = false,
            adapterAvailable = true, peerBusy = false, requiresGrant = false, grantValid = false, profile = profile)

    @Test fun `accepted request waits during connecting then retries after definite failure`() {
        val c = AudioConnectionCoordinator()
        assertTrue(c.submit(intent(), evidence()).single() is AudioConnectionCoordinator.Effect.Connect)
        assertTrue(c.observedProfile(headset, AudioConnectionCoordinator.Profile.CONNECTING).single() is AudioConnectionCoordinator.Effect.QueryProfile)
        c.reconciledProfile(headset, evidence(1, AudioConnectionCoordinator.Profile.CONNECTING))
        assertTrue(c.assess(evidence(10_000, AudioConnectionCoordinator.Profile.CONNECTING)).single() is AudioConnectionCoordinator.Effect.QueryProfile)
        assertTrue(c.reconciledProfile(headset, evidence(21_000)).single() is AudioConnectionCoordinator.Effect.Retry)
        assertTrue(c.assess(evidence(21_001)).single() is AudioConnectionCoordinator.Effect.Connect)
        assertTrue(c.commandRejected(1, evidence(22_000)).single() is AudioConnectionCoordinator.Effect.Retry)
        val result = c.assess(evidence(22_001)).single() as AudioConnectionCoordinator.Effect.Outcome
        assertEquals(AudioConnectionCoordinator.Result.FAILED_PROFILE, result.result)
        assertEquals(2, c.snapshot.commands)
    }

    @Test fun `external connection does not count as a dispatched command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        assertTrue(c.existingProfile(1, AudioConnectionCoordinator.Profile.CONNECTING).single() is AudioConnectionCoordinator.Effect.QueryProfile)
        assertEquals(0, c.snapshot.commands)
        assertTrue(c.snapshot.unresolved)
        assertTrue(c.reconciledProfile(headset, evidence(1, AudioConnectionCoordinator.Profile.CONNECTING)).isEmpty())
        assertTrue(c.reconciledProfile(headset, evidence(2)).single() is AudioConnectionCoordinator.Effect.Retry)
        assertTrue(c.assess(evidence(3)).single() is AudioConnectionCoordinator.Effect.Connect)
        assertEquals(1, c.snapshot.commands)
    }

    @Test fun `already connected profile can complete without a new command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        c.existingProfile(1, AudioConnectionCoordinator.Profile.CONNECTED)
        val outcome = c.reconciledProfile(headset, evidence(1, AudioConnectionCoordinator.Profile.CONNECTED)).single() as AudioConnectionCoordinator.Effect.Outcome
        assertEquals(AudioConnectionCoordinator.Result.CONNECTED, outcome.result)
        assertEquals(0, c.snapshot.commands)
    }

    @Test fun `stale disconnected broadcast does not release an in-flight command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        assertTrue(c.observedProfile(headset, AudioConnectionCoordinator.Profile.DISCONNECTED).single() is AudioConnectionCoordinator.Effect.QueryProfile)
        assertTrue(c.reconciledProfile(headset, evidence()).isEmpty())
        assertTrue(c.snapshot.unresolved)
        assertEquals(1, c.snapshot.commands)
    }

    @Test fun `late callback cannot resurrect expired intent`() {
        val c = AudioConnectionCoordinator()
        val outcome = c.submit(intent(expires = 15_000), evidence(now = 88_000)).single() as AudioConnectionCoordinator.Effect.Outcome
        assertEquals(AudioConnectionCoordinator.Result.STALE_INTENT, outcome.result)
    }

    @Test fun `replacement waits for old unresolved command and fresh grant`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        assertTrue(c.submit(intent(id = 2), evidence()).last() is AudioConnectionCoordinator.Effect.QueryProfile)
        assertTrue(c.reconciledProfile(headset, evidence(profile = AudioConnectionCoordinator.Profile.UNKNOWN)).isEmpty())
        c.reconciledProfile(headset, evidence(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        val grant = evidence(1).copy(requiresGrant = true)
        assertTrue(c.reconciledProfile(headset, grant).single() is AudioConnectionCoordinator.Effect.Coordinate)
        assertTrue(c.assess(grant.copy(grantValid = true, grantIntentId = 2, grantCommandNumber = 1)).single() is AudioConnectionCoordinator.Effect.Connect)
    }

    @Test fun `grant is bound to intent and cannot authorize a later retry`() {
        val c = AudioConnectionCoordinator()
        val shared = evidence().copy(requiresGrant = true)
        assertTrue(c.submit(intent(), shared).single() is AudioConnectionCoordinator.Effect.Coordinate)
        val firstGrant = shared.copy(grantValid = true, grantIntentId = 1, grantCommandNumber = 1)
        assertTrue(c.assess(firstGrant).single() is AudioConnectionCoordinator.Effect.Connect)
        assertTrue(c.mayDispatch(1, firstGrant))
        val afterFailure = c.commandRejected(1, firstGrant)
        assertTrue(afterFailure.single() is AudioConnectionCoordinator.Effect.Retry)
        assertTrue(c.assess(firstGrant).single() is AudioConnectionCoordinator.Effect.Coordinate)
        assertTrue(c.assess(firstGrant.copy(grantIntentId = 99)).single() is AudioConnectionCoordinator.Effect.Coordinate)
        assertTrue(c.assess(firstGrant.copy(grantCommandNumber = 2)).single() is AudioConnectionCoordinator.Effect.Connect)
    }

    @Test fun `call intent cannot be replaced by passive or media event`() {
        val c = AudioConnectionCoordinator()
        val call = intent().copy(trigger = AudioConnectionCoordinator.Trigger.CALL)
        c.submit(call, evidence())
        for (trigger in listOf(AudioConnectionCoordinator.Trigger.PASSIVE, AudioConnectionCoordinator.Trigger.MEDIA, AudioConnectionCoordinator.Trigger.MANUAL)) {
            val outcome = c.submit(intent(id = 2).copy(trigger = trigger), evidence()).single() as AudioConnectionCoordinator.Effect.Outcome
            assertEquals(AudioConnectionCoordinator.Result.SUPERSEDED, outcome.result)
            assertEquals(1L, c.snapshot.intent!!.id)
            assertTrue(c.snapshot.unresolved)
        }
    }

    @Test fun `timed out unresolved call still outranks passive intent`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent().copy(trigger = AudioConnectionCoordinator.Trigger.CALL), evidence())
        c.timeout(1)
        val result = c.submit(intent(id = 2), evidence()).single() as AudioConnectionCoordinator.Effect.Outcome
        assertEquals(AudioConnectionCoordinator.Result.SUPERSEDED, result.result)
        assertEquals(1L, c.snapshot.intent!!.id)
        assertTrue(c.snapshot.unresolved)
    }

    @Test fun `call supersedes passive but reconciles its in-flight command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        val effects = c.submit(intent(id = 2).copy(trigger = AudioConnectionCoordinator.Trigger.CALL), evidence())
        assertEquals(AudioConnectionCoordinator.Effect.Outcome(1, AudioConnectionCoordinator.Result.SUPERSEDED), effects.first())
        assertTrue(effects.last() is AudioConnectionCoordinator.Effect.QueryProfile)
        assertEquals(1L, c.snapshot.commandIntentId)
        assertEquals(AudioConnectionCoordinator.Trigger.CALL, c.snapshot.intent!!.trigger)
    }

    @Test fun `duplicate passive advertisements cannot reset an episode retry budget`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent().copy(episodeId = 7), evidence())
        val duplicate = c.submit(intent(id = 2).copy(episodeId = 7), evidence())
        assertEquals(AudioConnectionCoordinator.Effect.Outcome(2, AudioConnectionCoordinator.Result.SUPERSEDED), duplicate.single())
        assertEquals(1L, c.snapshot.intent!!.id)
        assertEquals(1, c.snapshot.commands)
        assertTrue(c.snapshot.unresolved)
    }

    @Test fun `duplicate intent does not reset budget or dispatch another command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        assertTrue(c.submit(intent(), evidence()).isEmpty())
        assertEquals(1, c.snapshot.commands)
    }

    @Test fun `late rejection resolves the original command after replacement`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        c.submit(intent(id = 2), evidence())
        assertTrue(c.commandRejected(1, evidence()).single() is AudioConnectionCoordinator.Effect.Connect)
        assertEquals(2L, c.snapshot.commandIntentId)
    }

    @Test fun `cancel and timeout never declare unresolved command released`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        val timeoutEffects = c.timeout(1)
        assertEquals(AudioConnectionCoordinator.Result.UNRESOLVED,
            (timeoutEffects.first() as AudioConnectionCoordinator.Effect.Outcome).result)
        assertTrue(timeoutEffects.last() is AudioConnectionCoordinator.Effect.QueryProfile)
        c.cancel(1)
        assertTrue(c.snapshot.unresolved)
        assertTrue(c.submit(intent(id = 2), evidence()).last() is AudioConnectionCoordinator.Effect.QueryProfile)
    }

    @Test fun `connected is observed success even after delayed completion`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence())
        val outcome = c.reconciledProfile(headset, evidence(11_000, AudioConnectionCoordinator.Profile.CONNECTED)).single() as AudioConnectionCoordinator.Effect.Outcome
        assertEquals(AudioConnectionCoordinator.Result.CONNECTED, outcome.result)
    }

    @Test fun `terminal intent cannot issue a later command`() {
        val c = AudioConnectionCoordinator()
        c.submit(intent(), evidence().copy(enabled = false))
        assertTrue(c.assess(evidence()).isEmpty())
        assertEquals(AudioConnectionCoordinator.Result.POLICY_DISABLED, c.snapshot.result)
    }

    @Test fun `stored earbuds and peer activity block passive connect`() {
        val c = AudioConnectionCoordinator()
        assertEquals(AudioConnectionCoordinator.Result.BUDS_STORED,
            (c.submit(intent(), evidence().copy(budsStored = true)).single() as AudioConnectionCoordinator.Effect.Outcome).result)
        assertEquals(AudioConnectionCoordinator.Result.PEER_BUSY,
            (c.submit(intent(id = 2), evidence().copy(peerBusy = true)).single() as AudioConnectionCoordinator.Effect.Outcome).result)
    }
}
