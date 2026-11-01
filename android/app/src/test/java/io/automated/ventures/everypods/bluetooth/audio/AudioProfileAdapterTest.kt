package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

class AudioProfileAdapterTest {
    private class Backend : AudioProfileAdapter.Backend {
        var listener: ((AudioProfileAdapter.Proxy?) -> Unit)? = null
        var starts = true
        override fun acquire(callback: (AudioProfileAdapter.Proxy?) -> Unit): Boolean {
            listener = callback
            return starts
        }
    }
    private class Proxy : AudioProfileAdapter.Proxy {
        var calls = 0
        var closes = 0
        var currentState = AudioConnectionCoordinator.Profile.DISCONNECTED
        var throwQuery = false
        var throwConnect = false
        var result: Boolean? = true
        override fun state(headset: String): AudioConnectionCoordinator.Profile {
            if (throwQuery) error("profile unavailable")
            return currentState
        }
        override fun connect(headset: String): Boolean? {
            calls++
            if (throwConnect) error("ambiguous binder failure")
            return result
        }
        override fun close() { closes++ }
    }

    @Test fun `late callback respects cancellation and closes the proxy`() {
        val backend = Backend()
        val adapter = AudioProfileAdapter(backend)
        var authorized = true
        val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
        adapter.connect("headset", { action -> if (authorized) action() }, outcomes::add)
        authorized = false
        val proxy = Proxy()
        backend.listener!!(proxy)
        assertEquals(0, proxy.calls)
        assertEquals(1, proxy.closes)
        assertEquals(listOf(AudioProfileAdapter.DispatchResult.NOT_DISPATCHED), outcomes)
    }

    @Test fun `duplicate proxy callbacks cannot dispatch a second command`() {
        val backend = Backend()
        val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
        AudioProfileAdapter(backend).connect("headset", { action -> action() }, outcomes::add)
        val first = Proxy(); val second = Proxy()
        backend.listener!!(first); backend.listener!!(second)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(listOf(AudioProfileAdapter.DispatchResult.ACCEPTED), outcomes)
    }

    @Test fun `connected connecting and unknown profiles never dispatch another command`() {
        val states = mapOf(
            AudioConnectionCoordinator.Profile.CONNECTED to AudioProfileAdapter.DispatchResult.ALREADY_CONNECTED,
            AudioConnectionCoordinator.Profile.CONNECTING to AudioProfileAdapter.DispatchResult.ALREADY_CONNECTING,
            AudioConnectionCoordinator.Profile.UNKNOWN to AudioProfileAdapter.DispatchResult.NOT_DISPATCHED,
            AudioConnectionCoordinator.Profile.DISCONNECTING to AudioProfileAdapter.DispatchResult.NOT_DISPATCHED,
        )
        states.forEach { (state, expected) ->
            val backend = Backend()
            val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
            AudioProfileAdapter(backend).connect("headset", { action -> action() }, outcomes::add)
            val proxy = Proxy().apply { currentState = state }
            backend.listener!!(proxy)
            assertEquals(0, proxy.calls)
            assertEquals(listOf(expected), outcomes)
        }
    }

    @Test fun `profile query failure is not a dispatched command`() {
        val backend = Backend()
        val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
        AudioProfileAdapter(backend).connect("headset", { action -> action() }, outcomes::add)
        val proxy = Proxy().apply { throwQuery = true }
        backend.listener!!(proxy)
        assertEquals(0, proxy.calls)
        assertEquals(listOf(AudioProfileAdapter.DispatchResult.NOT_DISPATCHED), outcomes)
        assertEquals(1, proxy.closes)
    }

    @Test fun `invocation exceptions retain unknown outcome`() {
        val backend = Backend()
        val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
        AudioProfileAdapter(backend).connect("headset", { action -> action() }, outcomes::add)
        val proxy = Proxy().apply { throwConnect = true }
        backend.listener!!(proxy)
        assertEquals(listOf(AudioProfileAdapter.DispatchResult.UNKNOWN), outcomes)
        assertEquals(1, proxy.closes)
    }

    @Test fun `unavailable proxy reports unknown profile and late proxy is closed`() {
        val backend = Backend().apply { starts = false }
        val profiles = mutableListOf<AudioConnectionCoordinator.Profile>()
        AudioProfileAdapter(backend).query("headset", profiles::add)
        val proxy = Proxy()
        backend.listener!!(proxy)
        assertEquals(listOf(AudioConnectionCoordinator.Profile.UNKNOWN), profiles)
        assertEquals(1, proxy.closes)
    }

    @Test fun `explicit false return is a rejection`() {
        val backend = Backend()
        val outcomes = mutableListOf<AudioProfileAdapter.DispatchResult>()
        AudioProfileAdapter(backend).connect("headset", { action -> action() }, outcomes::add)
        backend.listener!!(Proxy().apply { result = false })
        assertEquals(listOf(AudioProfileAdapter.DispatchResult.REJECTED), outcomes)
    }
}
