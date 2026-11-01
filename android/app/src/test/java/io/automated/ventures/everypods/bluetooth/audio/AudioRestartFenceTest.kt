package io.automated.ventures.everypods.bluetooth.audio

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioRestartFenceTest {
    private lateinit var prefs: android.content.SharedPreferences

    @Before fun reset() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("audio-fence-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private class Port(val store: AudioCommandFenceStore) : AudioConnectionDriver.Port {
        var state = AudioConnectionCoordinator.Profile.DISCONNECTED
        var commands = 0
        var failPersistence = false
        var failResolve = false
        var authorize: () -> Boolean = { true }
        var inCommand: () -> Unit = {}
        val queries = ArrayDeque<(AudioConnectionCoordinator.Evidence) -> Unit>()
        val outcomes = mutableListOf<AudioConnectionCoordinator.Result>()
        override fun loadPendingCommand() = store.load()
        override fun persistPendingCommand(headset: String) = if (failPersistence) null else store.begin(headset)
        override fun markPendingConnecting(token: String) = store.markConnecting(token)
        override fun resolvePendingCommand(token: String) = !failResolve && store.resolve(token)
        override fun evidence(headset: String, intent: AudioConnectionCoordinator.Intent?) =
            AudioConnectionCoordinator.Evidence(100, true, true, false, true, false, false, false, state)
        override fun query(headset: String, complete: (AudioConnectionCoordinator.Evidence) -> Unit) { queries.addLast(complete) }
        override fun coordinate(intent: AudioConnectionCoordinator.Intent, commandNumber: Int) = error("not shared")
        override fun connect(intentId: Long, headset: String, dispatch: (() -> Boolean, () -> Unit) -> Unit,
                             notDispatched: () -> Unit, rejected: () -> Unit, dispatched: () -> Unit) {
            var called = false
            dispatch(authorize) { inCommand(); called = true; commands++ }
            if (called) dispatched() else notDispatched()
        }
        override fun outcome(intentId: Long, result: AudioConnectionCoordinator.Result) { outcomes.add(result) }
        fun reply(profile: AudioConnectionCoordinator.Profile) {
            state = profile
            queries.removeFirst()(evidence("headset", null))
        }
    }
    private fun intent(id: Long) = AudioConnectionCoordinator.Intent(id, "headset",
        AudioConnectionCoordinator.Trigger.PASSIVE, 45_000)

    @Test fun `restart cannot repeat unresolved command until observed transition is reconciled`() {
        val first = Port(AudioCommandFenceStore(prefs))
        AudioConnectionDriver(AudioConnectionCoordinator(), first).submit(intent(1))
        assertEquals(1, first.commands)
        val original = first.store.load()!!
        val second = Port(AudioCommandFenceStore(prefs))
        val restarted = AudioConnectionDriver(AudioConnectionCoordinator(), second)
        assertTrue(restarted.hasUnresolvedCommand())
        restarted.submit(intent(2))
        second.reply(AudioConnectionCoordinator.Profile.DISCONNECTED)
        assertEquals(0, second.commands)
        assertEquals(original.token, second.store.load()!!.token)
        restarted.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        second.reply(AudioConnectionCoordinator.Profile.CONNECTING)
        assertTrue(second.store.load()!!.observedConnecting)
        restarted.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        second.reply(AudioConnectionCoordinator.Profile.DISCONNECTED)
        assertEquals(1, second.commands)
        assertNotEquals(original.token, second.store.load()!!.token)
    }

    @Test fun `authorization runs before marker and command runs only after marker is durable`() {
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        port.authorize = {
            assertFalse(driver.hasUnresolvedCommand())
            assertNull(port.store.load())
            true
        }
        port.inCommand = {
            assertTrue(driver.hasUnresolvedCommand())
            assertNotNull(AudioCommandFenceStore(prefs).load())
        }
        driver.submit(intent(1))
        assertEquals(1, port.commands)
        assertTrue(driver.hasUnresolvedCommand())
    }

    @Test fun `denied authorization never creates a persistent command marker`() {
        val port = Port(AudioCommandFenceStore(prefs)).apply { authorize = { false } }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.submit(intent(1))
        assertEquals(0, port.commands)
        assertNull(port.store.load())
        assertFalse(driver.hasUnresolvedCommand())
    }

    @Test fun `failed durable write denies OS dispatch`() {
        val port = Port(AudioCommandFenceStore(prefs)).apply { failPersistence = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.submit(intent(1))
        assertEquals(0, port.commands)
        assertEquals(listOf(AudioConnectionCoordinator.Result.STORAGE_UNAVAILABLE), port.outcomes)
        assertFalse(driver.hasUnresolvedCommand())
    }

    @Test fun `failed durable release blocks retry and keeps peer state uncertain`() {
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.submit(intent(1))
        port.failResolve = true
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        port.reply(AudioConnectionCoordinator.Profile.CONNECTING)
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        port.reply(AudioConnectionCoordinator.Profile.DISCONNECTED)
        assertEquals(1, port.commands)
        assertTrue(driver.hasUnresolvedCommand())
        assertNotNull(port.store.load())
        assertEquals(AudioConnectionCoordinator.Result.STORAGE_UNAVAILABLE, port.outcomes.last())
    }

    @Test fun `restart with recorded connecting can release a confirmed disconnected command`() {
        val store = AudioCommandFenceStore(prefs)
        val pending = store.begin("headset")!!
        assertTrue(store.markConnecting(pending.token))
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.reconcilePending()
        port.reply(AudioConnectionCoordinator.Profile.DISCONNECTED)
        assertFalse(driver.hasUnresolvedCommand())
        assertNull(store.load())
        assertEquals(0, port.commands)
    }

    @Test fun `token fencing and partial persisted records fail closed`() {
        val store = AudioCommandFenceStore(prefs)
        val pending = store.begin("headset")!!
        assertFalse(store.resolve("old-token"))
        assertEquals(pending, AudioCommandFenceStore(prefs).load())
        assertTrue(store.resolve(pending.token))
        prefs.edit().putString("headset", "headset").commit()
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        assertTrue(driver.hasUnresolvedCommand())
        driver.reconcilePending()
        assertTrue(port.queries.isEmpty())
        driver.submit(intent(1))
        assertEquals(0, port.commands)
        assertFalse(port.store.resolve(""))
        assertNotNull(port.store.load())
    }
    @Test fun `orphaned connecting marker cannot be mistaken for an empty fence`() {
        prefs.edit().putBoolean("observed_connecting", true).commit()
        val store = AudioCommandFenceStore(prefs)
        assertNotNull(store.load())
        assertNull(store.begin("headset"))
        val port = Port(store)
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        assertTrue(driver.hasUnresolvedCommand())
        driver.submit(intent(1))
        assertEquals(0, port.commands)
    }

    @Test fun `restored unresolved command cannot be replaced by a different headset`() {
        val store = AudioCommandFenceStore(prefs)
        val original = store.begin("old-headset")!!
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.submit(intent(2).copy(headset = "different-headset"))
        assertEquals(0, port.commands)
        assertTrue(driver.hasUnresolvedCommand())
        assertEquals(original.token, store.load()!!.token)
        assertEquals(listOf(AudioConnectionCoordinator.Result.UNRESOLVED), port.outcomes)
    }

    @Test fun `restart clears fence on confirmed connected profile without replay`() {
        val store = AudioCommandFenceStore(prefs)
        assertNotNull(store.begin("headset"))
        val port = Port(AudioCommandFenceStore(prefs))
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), port)
        driver.reconcilePending()
        port.reply(AudioConnectionCoordinator.Profile.CONNECTED)
        assertEquals(0, port.commands)
        assertNull(store.load())
        assertFalse(driver.hasUnresolvedCommand())
    }

}
