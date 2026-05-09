/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

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

package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.kavishdevar.librepods.data.AncProfile
import me.kavishdevar.librepods.data.NoiseControlMode
import java.util.Calendar

/**
 * Persistence + evaluation + application + watcher for time-based auto-ANC
 * profiles.
 *
 * Storage: SharedPreferences string set under [KEY]; one rule per entry,
 * pipe-delimited (see [AncProfile.serialize]).
 *
 * Engine: a recurring 60s tick re-evaluates the active profile and applies
 * its ANC mode if it changed since last apply. Apply uses the existing
 * SET_ANC_MODE broadcast, so it goes through the same code path as Quick
 * Settings / stem cycle.
 */
object AncProfilesManager {

    private const val TAG = "AncProfiles"
    private const val KEY = "anc_profiles_set"
    private const val KEY_ENABLED = "anc_profiles_enabled"
    private const val TICK_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ticker: Runnable? = null
    @Volatile private var lastAppliedAncOrdinal: Int = -1
    private val listeners = mutableListOf<() -> Unit>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) startWatcher(ctx) else stopWatcher()
    }

    fun loadAll(ctx: Context): List<AncProfile> {
        val raw = prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()
        return raw.mapNotNull { AncProfile.deserialize(it) }
            .sortedBy { it.startMinute }
    }

    fun saveAll(ctx: Context, profiles: List<AncProfile>) {
        prefs(ctx).edit().putStringSet(KEY, profiles.map { it.serialize() }.toSet()).apply()
        notifyListeners()
        // Re-evaluate immediately so a newly-added rule applies without waiting
        // for the next tick.
        if (isEnabled(ctx)) evaluateAndApply(ctx)
    }

    fun add(ctx: Context, profile: AncProfile) {
        saveAll(ctx, loadAll(ctx) + profile)
    }

    fun update(ctx: Context, profile: AncProfile) {
        saveAll(ctx, loadAll(ctx).map { if (it.id == profile.id) profile else it })
    }

    fun delete(ctx: Context, id: String) {
        saveAll(ctx, loadAll(ctx).filter { it.id != id })
    }

    fun currentMatch(ctx: Context, now: Calendar = Calendar.getInstance()): AncProfile? {
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return loadAll(ctx).firstOrNull { it.matches(mins) }
    }

    fun startWatcher(ctx: Context) {
        if (ticker != null) return
        val appCtx = ctx.applicationContext
        val task = object : Runnable {
            override fun run() {
                if (!isEnabled(appCtx)) {
                    ticker = null
                    return
                }
                evaluateAndApply(appCtx)
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = task
        // First evaluation immediately, then every minute.
        handler.post(task)
        Log.d(TAG, "Watcher started")
    }

    fun stopWatcher() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        lastAppliedAncOrdinal = -1
        Log.d(TAG, "Watcher stopped")
    }

    private fun evaluateAndApply(ctx: Context) {
        val match = currentMatch(ctx)
        if (match == null) {
            Log.d(TAG, "No matching profile right now")
            return
        }
        val ord = match.ancMode.ordinal
        if (ord == lastAppliedAncOrdinal) return  // no change
        Log.d(TAG, "Applying profile '${match.name}' → ${match.ancMode}")
        applyAncMode(ctx, match.ancMode)
        lastAppliedAncOrdinal = ord
    }

    fun applyAncMode(ctx: Context, mode: NoiseControlMode) {
        // SET_ANC_MODE expects "mode" extra in 1..4. Enum ordinals: OFF=0, NC=1,
        // TRANSPARENCY=2, ADAPTIVE=3 → wire value = ordinal + 1.
        ctx.applicationContext.sendBroadcast(
            Intent("me.kavishdevar.librepods.SET_ANC_MODE")
                .setPackage(ctx.packageName)
                .putExtra("mode", mode.ordinal + 1)
        )
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyListeners() { listeners.toList().forEach { it.invoke() } }
}
