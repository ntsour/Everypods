/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.UUID

/**
 * Option 1 — persisted "last successful audio holder" lease + feature flag.
 *
 * Local-only for v1 (mesh lease announce deferred). Each install trusts
 * [KEY_HOLDER_SELF], live CrossDevice holders, and successful local A2DP/takeover.
 */
object AudioLeasePrefs {
    private const val TAG = "AudioLeasePrefs"

    const val PREFS_NAME = "settings"

    /**
     * Feature flag. Missing key → [DEFAULT_ENABLED] (new installs ON).
     * Explicit `false` in prefs stays off — never force-enable users who opted out.
     */
    const val KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT = "lid_open_last_holder_autoconnect"

    /** Default when the preference key has never been written. */
    const val DEFAULT_ENABLED: Boolean = true

    const val KEY_HOLDER_SELF = "audio_lease_holder_self"
    const val KEY_UPDATED_AT_MS = "audio_lease_updated_at_ms"
    const val KEY_LEASE_ID = "audio_lease_id"

    /** Debounce window for lid-open connectAudio attempts. */
    const val LID_AUTOCONNECT_DEBOUNCE_MS: Long = 3_000L

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFeatureEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, DEFAULT_ENABLED)

    fun isFeatureEnabled(ctx: Context): Boolean = isFeatureEnabled(prefs(ctx))

    private const val KEY_POLICY_REVISION = "lid_open_policy_revision"
    private const val KEY_POLICY_ORIGIN = "lid_open_policy_origin"
    private const val KEY_POLICY_RECORDED_VALUE = "lid_open_policy_recorded_value"
    private const val KEY_POLICY_RECORDED_PERSISTED = "lid_open_policy_recorded_persisted"

    data class PolicySnapshot(
        val requestedEnabled: Boolean,
        val effectiveEnabled: Boolean,
        val overrideReason: String?,
        val revision: Long,
        val persisted: Boolean,
        val origin: String,
    ) {
        /** Existing readers mean the user's requested value. */
        val enabled: Boolean get() = requestedEnabled
    }

    /**
     * Reconcile writes made outside this repository, including while the process
     * was stopped. Never infer who made an external write or change its value.
     */
    @Synchronized fun policySnapshot(prefs: SharedPreferences, overrideReason: String? = null): PolicySnapshot {
        val requested = isFeatureEnabled(prefs)
        val persisted = prefs.contains(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT)
        var revision = prefs.getLong(KEY_POLICY_REVISION, 0L)
        var origin = prefs.getString(KEY_POLICY_ORIGIN, null) ?: "unrecorded"
        if (!prefs.contains(KEY_POLICY_RECORDED_VALUE) || !prefs.contains(KEY_POLICY_RECORDED_PERSISTED)) {
            origin = if (persisted) "existing_value" else "implicit_default"
            prefs.edit {
                putBoolean(KEY_POLICY_RECORDED_VALUE, requested)
                putBoolean(KEY_POLICY_RECORDED_PERSISTED, persisted)
                putString(KEY_POLICY_ORIGIN, origin)
            }
        } else {
            val previous = prefs.getBoolean(KEY_POLICY_RECORDED_VALUE, DEFAULT_ENABLED)
            val previouslyPersisted = prefs.getBoolean(KEY_POLICY_RECORDED_PERSISTED, false)
            if (previous != requested || previouslyPersisted != persisted) {
                revision++
                origin = "external_refresh"
                prefs.edit {
                    putLong(KEY_POLICY_REVISION, revision)
                    putString(KEY_POLICY_ORIGIN, origin)
                    putBoolean(KEY_POLICY_RECORDED_VALUE, requested)
                    putBoolean(KEY_POLICY_RECORDED_PERSISTED, persisted)
                }
                LidAutoconnectDiagnostics.logEvent("policy_write", mapOf(
                    "source" to origin, "previous" to previous, "feature" to requested,
                    "revision" to revision, "persisted" to persisted))
            }
        }
        val appliedOverride = overrideReason.takeIf { requested }
        return PolicySnapshot(requested, requested && appliedOverride == null, appliedOverride,
            revision, persisted, origin)
    }

    /** Seed the existing default only for a missing key, atomically with its origin. */
    @Synchronized fun ensureDefaultEnabled(prefs: SharedPreferences) {
        if (prefs.contains(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT)) return
        val before = policySnapshot(prefs)
        val revision = before.revision + 1L
        prefs.edit {
            putBoolean(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, DEFAULT_ENABLED)
            putLong(KEY_POLICY_REVISION, revision)
            putString(KEY_POLICY_ORIGIN, "initialization")
            putBoolean(KEY_POLICY_RECORDED_VALUE, DEFAULT_ENABLED)
            putBoolean(KEY_POLICY_RECORDED_PERSISTED, true)
        }
        LidAutoconnectDiagnostics.logEvent("policy_write", mapOf(
            "source" to "initialization", "previous" to before.requestedEnabled,
            "feature" to DEFAULT_ENABLED, "revision" to revision))
    }

    /** Observe the persisted policy used by both the settings screen and controller. */
    fun observePolicy(prefs: SharedPreferences, observer: (PolicySnapshot) -> Unit): AutoCloseable {
        val last = java.util.concurrent.atomic.AtomicReference<PolicySnapshot?>(null)
        fun emit() {
            val snapshot = policySnapshot(prefs)
            if (last.getAndSet(snapshot) != snapshot) observer(snapshot)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT || key == KEY_POLICY_REVISION || key == null) emit()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        emit()
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Synchronized fun setFeatureEnabled(prefs: SharedPreferences, enabled: Boolean) {
        val before = policySnapshot(prefs)
        if (before.persisted && before.requestedEnabled == enabled) return
        val revision = before.revision + 1L
        prefs.edit {
            putBoolean(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, enabled)
            putLong(KEY_POLICY_REVISION, revision)
            putString(KEY_POLICY_ORIGIN, "settings_ui")
            putBoolean(KEY_POLICY_RECORDED_VALUE, enabled)
            putBoolean(KEY_POLICY_RECORDED_PERSISTED, true)
        }
        LidAutoconnectDiagnostics.logEvent("policy_write", mapOf(
            "source" to "settings_ui", "previous" to before.requestedEnabled,
            "feature" to enabled, "revision" to revision))
    }

    fun isLeaseHolder(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_HOLDER_SELF, false)

    fun isLeaseHolder(ctx: Context): Boolean = isLeaseHolder(prefs(ctx))

    /** True once [KEY_HOLDER_SELF] has ever been written (claim or release). */
    fun leaseEverSet(prefs: SharedPreferences): Boolean =
        prefs.contains(KEY_HOLDER_SELF)

    fun leaseEverSet(ctx: Context): Boolean = leaseEverSet(prefs(ctx))

    /** CONNECTING is not ownership; failed attempts leave the last holder intact. */
    fun observeA2dpState(prefs: SharedPreferences, state: Int) {
        if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
            claimLease(prefs, "a2dp_connected")
        }
    }

    fun claimLease(prefs: SharedPreferences, reason: String) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HOLDER_SELF, true)
            .putLong(KEY_UPDATED_AT_MS, now)
            .putString(KEY_LEASE_ID, id)
            .apply()
        val claimLine = "<LogCollector:LidLease> lease_claim reason=$reason id=$id"
        Log.d(TAG, claimLine)
        LidAutoconnectDiagnostics.recordRawLine(claimLine)
    }

    fun claimLease(ctx: Context, reason: String) = claimLease(prefs(ctx), reason)

    fun releaseLease(prefs: SharedPreferences, reason: String) {
        val wasHolder = prefs.getBoolean(KEY_HOLDER_SELF, false)
        if (!wasHolder && prefs.contains(KEY_HOLDER_SELF)) {
            val skipLine = "<LogCollector:LidLease> lease_release skip reason=$reason already_false"
            Log.d(TAG, skipLine)
            LidAutoconnectDiagnostics.recordRawLine(skipLine)
            return
        }
        if (!wasHolder && !prefs.contains(KEY_HOLDER_SELF)) {
            val neverLine = "<LogCollector:LidLease> lease_release skip reason=$reason never_set"
            Log.d(TAG, neverLine)
            LidAutoconnectDiagnostics.recordRawLine(neverLine)
            return
        }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HOLDER_SELF, false)
            .putLong(KEY_UPDATED_AT_MS, now)
            .apply()
        val releaseLine = "<LogCollector:LidLease> lease_release reason=$reason"
        Log.d(TAG, releaseLine)
        LidAutoconnectDiagnostics.recordRawLine(releaseLine)
    }

    fun releaseLease(ctx: Context, reason: String) = releaseLease(prefs(ctx), reason)

    /**
     * Lease-aware [mayProactivelyConnect] when the feature flag is on.
     * Flag off callers should keep the baseline `!shared || a2dpOurs` formula.
     */
    fun mayProactivelyConnect(
        flagOn: Boolean,
        shared: Boolean,
        leaseHolder: Boolean,
        leaseEverSet: Boolean,
        a2dpOurs: Boolean,
        peersSynchronized: Boolean = !shared,
        peerHolding: Boolean = false,
        reservationGranted: Boolean = false,
    ): Boolean {
        if (!CrossDeviceReconnectPolicy.mayPassivelyAcquire(shared, peersSynchronized, peerHolding, reservationGranted)) return a2dpOurs
        if (!flagOn) {
            return !shared || a2dpOurs
        }
        return if (!shared) {
            // Standalone: leaseholder, or bootstrap before any lease exists.
            leaseHolder || !leaseEverSet
        } else {
            // Shared: leaseholder may cold-connect; non-holders only if A2DP already ours.
            leaseHolder || a2dpOurs
        }
    }

    /**
     * Whether this device may lid-open auto-grab.
     * Standalone bootstrap (flag on, no lease ever set) is allowed; shared without
     * lease is not (avoid multi-phone race).
     */
    fun mayLidAutoGrab(
        shared: Boolean,
        leaseHolder: Boolean,
        leaseEverSet: Boolean,
        peersSynchronized: Boolean = !shared,
        peerHolding: Boolean = false,
        reservationGranted: Boolean = false,
    ): Boolean {
        if (!CrossDeviceReconnectPolicy.mayPassivelyAcquire(shared, peersSynchronized, peerHolding, reservationGranted)) return false
        if (leaseHolder) return true
        if (!shared && !leaseEverSet) return true
        return false
    }
}
