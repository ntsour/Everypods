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
                leaseEverSet = true, a2dpOurs = false
            )
        )
        assertFalse(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = false,
                leaseEverSet = true, a2dpOurs = false
            )
        )
        assertTrue(
            AudioLeasePrefs.mayProactivelyConnect(
                flagOn = true, shared = true, leaseHolder = false,
                leaseEverSet = true, a2dpOurs = true
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
        assertTrue(
            AudioLeasePrefs.mayLidAutoGrab(shared = true, leaseHolder = true, leaseEverSet = true)
        )
        assertTrue(
            AudioLeasePrefs.mayLidAutoGrab(shared = false, leaseHolder = false, leaseEverSet = false)
        )
    }
}
