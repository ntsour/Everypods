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

    /**
     * Seed [DEFAULT_ENABLED] only when the key is absent. No-op if the user (or a
     * prior install) already wrote true/false.
     */
    fun ensureDefaultEnabled(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT)) {
            prefs.edit().putBoolean(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, DEFAULT_ENABLED).apply()
        }
    }

    fun setFeatureEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LID_OPEN_LAST_HOLDER_AUTOCONNECT, enabled).apply()
    }

    fun isLeaseHolder(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_HOLDER_SELF, false)

    fun isLeaseHolder(ctx: Context): Boolean = isLeaseHolder(prefs(ctx))

    /** True once [KEY_HOLDER_SELF] has ever been written (claim or release). */
    fun leaseEverSet(prefs: SharedPreferences): Boolean =
        prefs.contains(KEY_HOLDER_SELF)

    fun leaseEverSet(ctx: Context): Boolean = leaseEverSet(prefs(ctx))

    fun claimLease(prefs: SharedPreferences, reason: String) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HOLDER_SELF, true)
            .putLong(KEY_UPDATED_AT_MS, now)
            .putString(KEY_LEASE_ID, id)
            .apply()
        Log.d(TAG, "<LogCollector:LidLease> lease_claim reason=$reason id=$id")
    }

    fun claimLease(ctx: Context, reason: String) = claimLease(prefs(ctx), reason)

    fun releaseLease(prefs: SharedPreferences, reason: String) {
        val wasHolder = prefs.getBoolean(KEY_HOLDER_SELF, false)
        if (!wasHolder && prefs.contains(KEY_HOLDER_SELF)) {
            Log.d(TAG, "<LogCollector:LidLease> lease_release skip reason=$reason already_false")
            return
        }
        if (!wasHolder && !prefs.contains(KEY_HOLDER_SELF)) {
            Log.d(TAG, "<LogCollector:LidLease> lease_release skip reason=$reason never_set")
            return
        }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HOLDER_SELF, false)
            .putLong(KEY_UPDATED_AT_MS, now)
            .apply()
        Log.d(TAG, "<LogCollector:LidLease> lease_release reason=$reason")
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
    ): Boolean {
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
    ): Boolean {
        if (leaseHolder) return true
        if (!shared && !leaseEverSet) return true
        return false
    }
}
