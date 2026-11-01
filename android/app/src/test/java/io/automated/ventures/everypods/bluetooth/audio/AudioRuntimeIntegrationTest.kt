package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

/** End-to-end decision/effect/adapter test with a deterministic profile-proxy backend. */
class AudioRuntimeIntegrationTest {
    private class Backend : AudioProfileAdapter.Backend {
        val callbacks = ArrayDeque<(AudioProfileAdapter.Proxy?) -> Unit>()
        var state = AudioConnectionCoordinator.Profile.DISCONNECTED
        var actualCommands = 0
        override fun acquire(callback: (AudioProfileAdapter.Proxy?) -> Unit): Boolean {
            callbacks.addLast(callback); return true
        }
        fun deliver() {
            callbacks.removeFirst()(object : AudioProfileAdapter.Proxy {
                override fun state(headset: String) = state
                override fun connect(headset: String): Boolean { actualCommands++; return true }
                override fun close() = Unit
            })
        }
    }
    @Test fun `recorded long profile failure drives one bounded retry after reconciliation`() {
        val backend = Backend()
        val adapter = AudioProfileAdapter(backend)
        var now = 0L
        val coordinator = AudioConnectionCoordinator()
        lateinit var driver: AudioConnectionDriver
        val port = object : AudioConnectionDriver.Port {
            override fun evidence(headset: String, intent: AudioConnectionCoordinator.Intent?) =
                AudioConnectionCoordinator.Evidence(now, true, true, false, true, false, false, false, backend.state)
            override fun query(headset: String, complete: (AudioConnectionCoordinator.Evidence) -> Unit) {
                adapter.query(headset) { complete(evidence(headset, coordinator.snapshot.intent).copy(profile = it)) }
            }
            override fun coordinate(intent: AudioConnectionCoordinator.Intent, commandNumber: Int) = error("offline test")
            override fun connect(intentId: Long, headset: String, dispatch: (() -> Boolean, () -> Unit) -> Unit,
                                 notDispatched: () -> Unit, rejected: () -> Unit, dispatched: () -> Unit) {
                adapter.connect(headset, { command -> dispatch({ true }, command) }) {
                    when (it) {
                        AudioProfileAdapter.DispatchResult.NOT_DISPATCHED -> notDispatched()
                        AudioProfileAdapter.DispatchResult.REJECTED -> rejected()
                        AudioProfileAdapter.DispatchResult.ALREADY_CONNECTED -> driver.onExistingProfile(intentId, AudioConnectionCoordinator.Profile.CONNECTED)
                        AudioProfileAdapter.DispatchResult.ALREADY_CONNECTING -> driver.onExistingProfile(intentId, AudioConnectionCoordinator.Profile.CONNECTING)
                        else -> dispatched()
                    }
                }
            }
            override fun outcome(intentId: Long, result: AudioConnectionCoordinator.Result) = Unit
        }
        driver = AudioConnectionDriver(coordinator, port)
        driver.submit(AudioConnectionCoordinator.Intent(1, "headset", AudioConnectionCoordinator.Trigger.PASSIVE, 45_000))
        assertEquals(0, backend.actualCommands)
        backend.deliver() // async proxy; first command accepted
        assertEquals(1, backend.actualCommands)
        backend.state = AudioConnectionCoordinator.Profile.CONNECTING
        driver.onProfileBroadcast("headset", backend.state)
        backend.deliver() // fresh query sees CONNECTING
        assertEquals(1, backend.actualCommands)
        now = 21_000
        backend.state = AudioConnectionCoordinator.Profile.DISCONNECTED
        driver.onProfileBroadcast("headset", backend.state)
        backend.deliver() // fresh query sees terminal profile failure
        assertEquals(1, backend.actualCommands)
        backend.deliver() // second async proxy issues one retry
        assertEquals(2, backend.actualCommands)
    }
}
