/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingPrefs
import io.automated.ventures.everypods.utils.GymModePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Implemented by AirPodsService: the existing Gym Mode / heart-rate code paths. */
interface GymAutomationHost {
    fun airPodsReady(): Boolean
    fun heartRateActive(): Boolean
    /** Same service command the case-art shortcut uses for an explicit selection. */
    fun startHeartRateLikeShortcut(method: HeartRateSharingMethod)
    fun stopHeartRateFromAutomation()
}

/** Live status shown in the editor. */
enum class GymAutomationStatus {
    OFF, NEEDS_SETUP, WAITING_ARRIVAL, WAITING_AIRPODS, ACTIVE, PAUSED, LEAVING, LOCATION_UNAVAILABLE
}

/**
 * Serial (main-thread) executor for [GymAutomationReducer]. Persists the journal after
 * every event and runs effects through the existing Gym Mode preference and the
 * service's heart-rate commands. Logs never include coordinates.
 */
object GymAutomationCoordinator {
    const val TAG = "EveryPodsGymAuto"
    private val main = Handler(Looper.getMainLooper())
    private var host: GymAutomationHost? = null
    private var coldStarted = false
    private val graceRunnable = Runnable { appCtx?.let { dispatch(it, Event.GraceCheck) } }
    private var appCtx: Context? = null

    /** True while the coordinator itself writes gym_mode_enabled (not a user override). */
    @Volatile var writingGym = false
        private set
    @Volatile private var stoppingHr = false

    private val mutableStatus = MutableStateFlow(GymAutomationStatus.OFF)
    val status: StateFlow<GymAutomationStatus> = mutableStatus.asStateFlow()

    fun attach(ctx: Context, h: GymAutomationHost) = onMain {
        host = h
        dispatch(ctx, Event.Reconcile)
        GymGeofenceRegistrar.sync(ctx)
    }

    fun detach(h: GymAutomationHost) = onMain { if (host === h) host = null }

    /** Called by the service when the user (not this coordinator) stops heart rate. */
    fun onHeartRateStopped(ctx: Context) { if (!stoppingHr) dispatch(ctx, Event.ManualHrStop) }

    /** Called from the gym_mode_enabled listener. */
    fun onGymModeChanged(ctx: Context, enabled: Boolean) {
        if (!writingGym && !enabled) dispatch(ctx, Event.ManualGymOff)
    }

    fun dispatch(ctx: Context, event: Event) = onMain {
        val c = ctx.applicationContext
        appCtx = c
        if (!coldStarted) {
            coldStarted = true
            if (event != Event.ColdStart) run(c, Event.ColdStart)
        }
        run(c, event)
    }

    private fun run(ctx: Context, event: Event) {
        val rule = GymAutomationRepository.rule(ctx)
        val journal = GymAutomationRepository.journal(ctx)
        val h = host
        val snapshot = Snapshot(
            now = SystemClock.elapsedRealtime(),
            bootId = bootId(ctx),
            ruleEnabled = rule.enabled,
            ruleRevision = rule.revision,
            hrActionEnabled = rule.hrActionEnabled,
            restoreOnExit = rule.restoreOnExit,
            airPodsReady = h?.airPodsReady() == true,
            gymOn = GymModePrefs.isEnabled(ctx),
            hrActive = h?.heartRateActive() == true,
            hrSelectedMethod = HeartRateSharingPrefs.configuredMethod(ctx)?.name,
        )
        val result = GymAutomationReducer.reduce(journal, event, snapshot)
        if (result.journal != journal) GymAutomationRepository.saveJournal(ctx, result.journal)
        if (result.effects.isNotEmpty() || event !is Event.Reconcile) {
            Log.i(TAG, "event=${event.javaClass.simpleName} presence=${result.journal.presence} " +
                "effects=${result.effects.map { it.javaClass.simpleName }} ready=${snapshot.airPodsReady}")
        }
        result.effects.forEach { execute(ctx, it) }
        mutableStatus.value = statusOf(rule, result.journal, h)
    }

    private fun execute(ctx: Context, effect: Effect) {
        when (effect) {
            Effect.EnableGym -> writeGym(ctx, true)
            Effect.DisableGym -> writeGym(ctx, false)
            is Effect.StartHr -> runCatching { HeartRateSharingMethod.valueOf(effect.method) }.getOrNull()
                ?.let { host?.startHeartRateLikeShortcut(it) }
            Effect.StopHr -> { stoppingHr = true; try { host?.stopHeartRateFromAutomation() } finally { stoppingHr = false } }
            is Effect.ScheduleGraceCheck -> {
                main.removeCallbacks(graceRunnable)
                main.postDelayed(graceRunnable, (effect.atElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
            Effect.CancelGraceCheck -> main.removeCallbacks(graceRunnable)
        }
    }

    private fun writeGym(ctx: Context, on: Boolean) {
        writingGym = true
        // On the main thread the preference listener runs synchronously inside apply().
        try { GymModePrefs.setEnabled(ctx, on) } finally { writingGym = false }
    }

    fun statusOf(rule: GymAutomationRule, j: Journal, h: GymAutomationHost?): GymAutomationStatus = when {
        !rule.enabled -> if (rule.hasLocation) GymAutomationStatus.OFF else GymAutomationStatus.NEEDS_SETUP
        !GymGeofenceRegistrar.hasPermissions(appCtx ?: return GymAutomationStatus.OFF) -> GymAutomationStatus.NEEDS_SETUP
        j.locationUnavailable -> GymAutomationStatus.LOCATION_UNAVAILABLE
        j.presence != Presence.INSIDE -> GymAutomationStatus.WAITING_ARRIVAL
        j.departureDeadline != null -> GymAutomationStatus.LEAVING
        j.gymSuppressed -> GymAutomationStatus.PAUSED
        !j.applied || h?.airPodsReady() != true && !j.applied -> GymAutomationStatus.WAITING_AIRPODS
        else -> GymAutomationStatus.ACTIVE
    }

    fun refreshStatus(ctx: Context) = onMain {
        appCtx = ctx.applicationContext
        mutableStatus.value = statusOf(GymAutomationRepository.rule(ctx), GymAutomationRepository.journal(ctx), host)
    }

    private fun bootId(ctx: Context): String =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
