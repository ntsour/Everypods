package io.automated.ventures.everypods.utils

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioLeasePrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(AudioLeasePrefs.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `feature flag defaults true when key missing`() {
        assertTrue(AudioLeasePrefs.isFeatureEnabled(prefs))
        assertFalse(prefs.contains(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT))
    }

    @Test
    fun `ensureDefaultEnabled seeds true only when missing`() {
        AudioLeasePrefs.ensureDefaultEnabled(prefs)
        assertTrue(prefs.contains(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT))
        assertTrue(AudioLeasePrefs.isFeatureEnabled(prefs))

        AudioLeasePrefs.setFeatureEnabled(prefs, false)
        AudioLeasePrefs.ensureDefaultEnabled(prefs)
        assertFalse(AudioLeasePrefs.isFeatureEnabled(prefs))
    }

    @Test
    fun `policy revisions change only on explicit changes and preserve opt out`() {
        val initial = AudioLeasePrefs.policySnapshot(prefs)
        assertTrue(initial.enabled)
        assertFalse(initial.persisted)
        AudioLeasePrefs.setFeatureEnabled(prefs, false)
        val disabled = AudioLeasePrefs.policySnapshot(prefs)
        assertFalse(disabled.enabled)
        assertTrue(disabled.persisted)
        org.junit.Assert.assertEquals(initial.revision + 1, disabled.revision)
        AudioLeasePrefs.setFeatureEnabled(prefs, false)
        org.junit.Assert.assertEquals(disabled.revision, AudioLeasePrefs.policySnapshot(prefs).revision)
        AudioLeasePrefs.ensureDefaultEnabled(prefs)
        assertFalse(AudioLeasePrefs.policySnapshot(prefs).enabled)
    }

    @Test
    fun `policy observer follows independent changes and unregisters cleanly`() {
        val observed = mutableListOf<AudioLeasePrefs.PolicySnapshot>()
        val subscription = AudioLeasePrefs.observePolicy(prefs) { observed.add(it) }
        assertTrue(observed.single().enabled)
        prefs.edit().putBoolean(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, false).commit()
        assertFalse(observed.last().enabled)
        val revision = observed.last().revision
        AudioLeasePrefs.setFeatureEnabled(prefs, true)
        assertTrue(observed.last().enabled)
        org.junit.Assert.assertEquals(revision + 1, observed.last().revision)
        subscription.close()
        val count = observed.size
        prefs.edit().putBoolean(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, false).commit()
        org.junit.Assert.assertEquals(count, observed.size)
        assertFalse(AudioLeasePrefs.policySnapshot(prefs).enabled)
    }

    @Test
    fun `policy preserves existing choice and records externally changed values across recreation`() {
        prefs.edit().putBoolean(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, false).commit()
        val existing = AudioLeasePrefs.policySnapshot(prefs)
        assertFalse(existing.requestedEnabled)
        assertTrue(existing.persisted)
        org.junit.Assert.assertEquals("existing_value", existing.origin)
        AudioLeasePrefs.ensureDefaultEnabled(prefs)
        assertFalse(AudioLeasePrefs.policySnapshot(prefs).requestedEnabled)
        // Simulate a writer that operates while neither the service nor UI observes.
        prefs.edit().putBoolean(AudioLeasePrefs.KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, true).commit()
        val recreatedPrefs = context.getSharedPreferences(AudioLeasePrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val refreshed = AudioLeasePrefs.policySnapshot(recreatedPrefs)
        assertTrue(refreshed.requestedEnabled)
        org.junit.Assert.assertEquals(existing.revision + 1, refreshed.revision)
        org.junit.Assert.assertEquals("external_refresh", refreshed.origin)
        org.junit.Assert.assertEquals(refreshed, AudioLeasePrefs.policySnapshot(recreatedPrefs))
    }

    @Test
    fun `temporary override changes effective state without changing user preference`() {
        AudioLeasePrefs.setFeatureEnabled(prefs, true)
        val before = AudioLeasePrefs.policySnapshot(prefs)
        val paused = AudioLeasePrefs.policySnapshot(prefs, "case_episode_inactive")
        assertTrue(paused.requestedEnabled)
        assertFalse(paused.effectiveEnabled)
        org.junit.Assert.assertEquals("case_episode_inactive", paused.overrideReason)
        org.junit.Assert.assertEquals(before.revision, paused.revision)
        assertTrue(AudioLeasePrefs.policySnapshot(prefs).effectiveEnabled)
        AudioLeasePrefs.setFeatureEnabled(prefs, false)
        val disabled = AudioLeasePrefs.policySnapshot(prefs, "case_episode_inactive")
        assertFalse(disabled.requestedEnabled)
        assertFalse(disabled.effectiveEnabled)
        org.junit.Assert.assertNull(disabled.overrideReason)
    }

    @Test
    fun `concurrent initialization cannot undo a user opt out`() {
        repeat(10) {
            prefs.edit().clear().commit()
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val initialize = executor.submit { start.await(); AudioLeasePrefs.ensureDefaultEnabled(prefs) }
                val optOut = executor.submit { start.await(); AudioLeasePrefs.setFeatureEnabled(prefs, false) }
                start.countDown()
                initialize.get(3, java.util.concurrent.TimeUnit.SECONDS)
                optOut.get(3, java.util.concurrent.TimeUnit.SECONDS)
                assertFalse(AudioLeasePrefs.policySnapshot(prefs).requestedEnabled)
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `explicit false stays off`() {
        AudioLeasePrefs.setFeatureEnabled(prefs, false)
        assertFalse(AudioLeasePrefs.isFeatureEnabled(prefs))
    }

    @Test
    fun `claim sets holder id and timestamp`() {
        AudioLeasePrefs.claimLease(prefs, "test_claim")
        assertTrue(AudioLeasePrefs.isLeaseHolder(prefs))
        assertTrue(AudioLeasePrefs.leaseEverSet(prefs))
        assertTrue(prefs.contains(AudioLeasePrefs.KEY_LEASE_ID))
        assertTrue(prefs.getLong(AudioLeasePrefs.KEY_UPDATED_AT_MS, 0L) > 0L)
    }

    @Test
    fun `release clears holder but keeps key`() {
        AudioLeasePrefs.claimLease(prefs, "claim")
        AudioLeasePrefs.releaseLease(prefs, "yield")
        assertFalse(AudioLeasePrefs.isLeaseHolder(prefs))
        assertTrue(AudioLeasePrefs.leaseEverSet(prefs))
    }

    @Test
    fun `release no-op when never set`() {
        AudioLeasePrefs.releaseLease(prefs, "noop")
        assertFalse(AudioLeasePrefs.leaseEverSet(prefs))
    }

    @Test
    fun `mayProactivelyConnect flag off matches baseline`() {
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = false, shared = false, leaseHolder = false,
                leaseEverSet = false, a2dpOurs = false
            )
        )
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = false, shared = true, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false
            )
        )
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = false, shared = true, leaseHolder = false,
                leaseEverSet = false, a2dpOurs = true
            )
        )
    }

    @Test
    fun `mayProactivelyConnect flag on shared leaseholder cold path`() {
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false, peersSynchronized = true, reservationGranted = true,
            )
        )
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = false,
                leaseEverSet = true, a2dpOurs = false, peersSynchronized = true,
            )
        )
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = false,
                leaseEverSet = true, a2dpOurs = true, peersSynchronized = true,
            )
        )
    }

    @Test
    fun `mayProactivelyConnect flag on standalone bootstrap`() {
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = false, leaseHolder = false,
                leaseEverSet = false, a2dpOurs = false
            )
        )
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = false, leaseHolder = false,
                leaseEverSet = true, a2dpOurs = false
            )
        )
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = false, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false
            )
        )
    }

    @Test
    fun `mayLidAutoGrab shared requires lease`() {
        assertFalse(
            AudioLeasePrefs.mayLidAutoGrab(shared = true, leaseHolder = false, leaseEverSet = false)
        )
        assertFalse(
            AudioLeasePrefs.mayLidAutoGrab(
                shared = true, leaseHolder = true, leaseEverSet = true,
                peersSynchronized = false,
            ),
        )
        assertFalse(
            AudioLeasePrefs.mayLidAutoGrab(
                shared = true, leaseHolder = true, leaseEverSet = true,
                peersSynchronized = true, peerHolding = true,
            ),
        )
        assertTrue(
            AudioLeasePrefs.mayLidAutoGrab(
                shared = true, leaseHolder = true, leaseEverSet = true,
                peersSynchronized = true, reservationGranted = true,
            )
        )
        assertTrue(
            AudioLeasePrefs.mayLidAutoGrab(shared = false, leaseHolder = false, leaseEverSet = false)
        )
    }

    @Test
    fun `shared proactive grab requires synchronized released peers`() {
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false, peersSynchronized = false,
            ),
        )
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false, peersSynchronized = true,
                peerHolding = true,
            ),
        )
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = true,
                leaseEverSet = true, a2dpOurs = false, peersSynchronized = true, reservationGranted = true,
            ),
        )
    }
}
