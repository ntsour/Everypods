/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers an inner ENTER and an outer EXIT geofence for every located zone (max 20 fences). */
object GymGeofenceRegistrar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var syncing = false
    private var syncAgain = false
    private var fullRefreshInFlight = false
    private var checkedThisProcess = false
    private var locationWasAvailable: Boolean? = null
    private var invalidation = 0L
    private val completions = mutableListOf<() -> Unit>()
    private const val CONFIRMED = "geofences_confirmed"
    private const val POSSIBLE = "geofences_possible"
    private const val REFRESH = "geofences_refresh"
    private const val LAST_SUCCESS = "geofences_last_success"
    private const val RETRY_ATTEMPT = "geofences_retry_attempt"

    fun hasPermissions(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Same shape as schema 1 ("gym:<ruleId>:<rev>:in"); the migrated zone keeps the rule id.
    fun innerId(zone: GymZone) = "gym:${zone.id}:${zone.revision}:in"
    fun outerId(zone: GymZone) = "gym:${zone.id}:${zone.revision}:out"

    data class Parsed(val zoneId: String, val revision: Long, val inner: Boolean)

    /** Parses a request id for a zone that still exists; the reducer checks the revision. */
    fun parse(rule: GymAutomationRule, requestId: String): Parsed? {
        val parts = requestId.split(":")
        if (parts.size != 4 || parts[0] != "gym" || rule.zone(parts[1]) == null) return null
        val revision = parts[2].toLongOrNull() ?: return null
        return when (parts[3]) { "in" -> Parsed(parts[1], revision, true); "out" -> Parsed(parts[1], revision, false); else -> null }
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 4711, Intent(ctx, GymGeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun onForeground(ctx: Context) {
        // Boot or package replacement may already be restoring the same fences.
        if (syncing) return
        val prefs = GymAutomationRepository.prefs(ctx)
        val rule = GymAutomationRepository.rule(ctx)
        if (!rule.enabled) {
            if (!prefs.getStringSet(POSSIBLE, emptySet()).isNullOrEmpty()) sync(ctx)
            return
        }
        // A persisted success is historical. Reconcile once on first interactive launch
        // of a new process so reopening after a force-stop restores the fences.
        val desired = rule.validZones.flatMap { listOf(innerId(it), outerId(it)) }.toSet()
        if (!checkedThisProcess || prefs.getBoolean(REFRESH, false) ||
            prefs.getStringSet(CONFIRMED, emptySet()) != desired || prefs.getStringSet(POSSIBLE, emptySet()) != desired) {
            sync(ctx, force = !checkedThisProcess)
        }
    }

    fun onLocationSettingsChanged(ctx: Context) {
        if (!GymAutomationRepository.rule(ctx).enabled) return
        val available = locationAvailable(ctx)
        val previous = locationWasAvailable
        locationWasAvailable = available
        if (!available) markInvalid(ctx)
        else if (previous == false || GymAutomationRepository.prefs(ctx).getBoolean(REFRESH, false)) sync(ctx)
    }

    fun markInvalid(ctx: Context) {
        invalidation++
        GymAutomationRepository.prefs(ctx).edit().putBoolean(REFRESH, true).commit()
    }

    fun sync(ctx: Context, force: Boolean = false, retry: Boolean = false, onComplete: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        scope.launch {
            val prefs = GymAutomationRepository.prefs(app)
            if (force && !fullRefreshInFlight) markInvalid(app)
            if (!retry) prefs.edit().remove(RETRY_ATTEMPT).apply()
            onComplete?.let(completions::add)
            if (syncing) {
                syncAgain = true
                GymAutomationDiagnostics.record(app, "registration queued")
                return@launch
            }
            syncing = true
            var retryNeeded: Boolean
            try {
                do {
                    syncAgain = false
                    retryNeeded = try {
                        runSync(app)
                    } catch (e: Exception) {
                        markInvalid(app)
                        GymAutomationDiagnostics.record(app, "registration failed stage=prepare error=${e.javaClass.simpleName}")
                        true
                    }
                } while (syncAgain)
            } finally {
                syncing = false
                val callbacks = completions.toList()
                completions.clear()
                callbacks.forEach { runCatching(it) }
            }
            if (retryNeeded) {
                val attempt = prefs.getInt(RETRY_ATTEMPT, 0)
                if (attempt < 3) {
                    prefs.edit().putInt(RETRY_ATTEMPT, attempt + 1).commit()
                    GymAutomationJobs.retryRegistration(app, attempt)
                } else {
                    GymAutomationDiagnostics.record(app, "registration retry exhausted")
                }
            } else {
                prefs.edit().remove(RETRY_ATTEMPT).apply()
                GymAutomationJobs.cancelRegistrationRetry(app)
            }
        }
    }

    private fun locationAvailable(ctx: Context): Boolean =
        hasPermissions(ctx) && ctx.getSystemService(LocationManager::class.java).isLocationEnabled

    /** Returns true when a delayed retry could help. */
    @SuppressLint("MissingPermission")
    private suspend fun runSync(ctx: Context): Boolean {
        val prefs = GymAutomationRepository.prefs(ctx)
        val rule = GymAutomationRepository.rule(ctx)
        val available = locationAvailable(ctx)
        val recovered = locationWasAvailable == false && available
        locationWasAvailable = available
        if (rule.enabled && !available) {
            markInvalid(ctx)
            GymAutomationDiagnostics.record(ctx, "registration blocked ${GymAutomationDiagnostics.environment(ctx)}")
            return false
        }
        val zones = rule.validZones
        val desired = if (rule.enabled) zones.flatMap { listOf(innerId(it), outerId(it)) }.toSet() else emptySet()
        val confirmed = prefs.getStringSet(CONFIRMED, emptySet()).orEmpty().toSet()
        val possible = prefs.getStringSet(POSSIBLE, emptySet()).orEmpty().toSet() + confirmed
        val refresh = recovered || prefs.getBoolean(REFRESH, true)
        val registrationGeneration = invalidation
        val additions = if (refresh) desired else desired - confirmed
        val obsolete = possible - desired
        var stage = "start"
        try {
            val client = LocationServices.getGeofencingClient(ctx)
            if (!rule.enabled) {
                stage = "remove_disabled"
                withTimeout(6_000) { client.removeGeofences(pendingIntent(ctx)).awaitCompletion() }
                prefs.edit().remove(CONFIRMED).remove(POSSIBLE).remove(REFRESH).remove(LAST_SUCCESS).commit()
                checkedThisProcess = false
                GymAutomationDiagnostics.record(ctx, "registration removed reason=rule_off")
                return false
            }
            if (additions.isNotEmpty()) {
                stage = "add"
                fullRefreshInFlight = refresh
                // Track potentially added IDs before the call, including partial failures or process death.
                prefs.edit().putStringSet(POSSIBLE, possible + additions).commit()
                val fences = zones.flatMapIndexed { index, zone ->
                    if (innerId(zone) in additions || outerId(zone) in additions) {
                        GymAutomationDiagnostics.record(ctx, "registration zone=$index rev=${zone.revision} innerM=${zone.geofenceRadiusMeters} outerM=${zone.exitRadiusMeters}")
                    }
                    listOf(
                        fence(zone, innerId(zone), zone.geofenceRadiusMeters, Geofence.GEOFENCE_TRANSITION_ENTER),
                        fence(zone, outerId(zone), zone.exitRadiusMeters, Geofence.GEOFENCE_TRANSITION_EXIT),
                    )
                }.filter { it.requestId in additions }
                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(fences).build()
                GymAutomationDiagnostics.record(ctx, "registration add rev=${rule.revision} fences=${fences.size} ${GymAutomationDiagnostics.environment(ctx)}")
                try {
                    withTimeout(6_000) { client.addGeofences(request, pendingIntent(ctx)).awaitCompletion() }
                } finally {
                    fullRefreshInFlight = false
                }
                prefs.edit().putStringSet(CONFIRMED, desired)
                    .putBoolean(REFRESH, registrationGeneration != invalidation)
                    .putLong(LAST_SUCCESS, System.currentTimeMillis()).commit()
                checkedThisProcess = true
                GymAutomationDiagnostics.record(ctx, "registration success rev=${rule.revision} zones=${zones.size}")
            }
            if (obsolete.isNotEmpty()) {
                stage = "remove_obsolete"
                withTimeout(6_000) { client.removeGeofences(obsolete.toList()).awaitCompletion() }
                prefs.edit().putStringSet(CONFIRMED, desired).putStringSet(POSSIBLE, desired).commit()
                GymAutomationDiagnostics.record(ctx, "registration removed obsolete=${obsolete.size}")
            }
            return prefs.getBoolean(REFRESH, false)
        } catch (e: Exception) {
            fullRefreshInFlight = false
            val code = (e as? ApiException)?.statusCode
            GymAutomationDiagnostics.record(ctx, "registration failed stage=$stage code=$code error=${e.javaClass.simpleName}")
            return hasPermissions(ctx) && e !is SecurityException &&
                code != com.google.android.gms.location.GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION
        }
    }

    private fun fence(zone: GymZone, id: String, radius: Int, transition: Int): Geofence = Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(zone.latitude!!, zone.longitude!!, radius.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(transition)
        .build()

    private suspend fun Task<Void>.awaitCompletion() = suspendCancellableCoroutine<Unit> { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) continuation.resume(Unit)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Geofence task canceled"))
        }
    }
}
