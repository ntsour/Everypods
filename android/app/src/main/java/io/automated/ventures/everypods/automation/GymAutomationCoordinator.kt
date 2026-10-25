/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingPrefs
import io.automated.ventures.everypods.utils.GymModePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Implemented by AirPodsService: the existing Gym Mode / heart-rate code paths. */
interface GymAutomationHost {
    fun airPodsReady(): Boolean
    fun heartRateActive(): Boolean
    /** Live owner of the running session, from the persisted owner record. */
    fun heartRateOwner(): HeartRateSource?
    /** The shared case-shortcut start step (AirPodsService.ensureHeartRateStarted). */
    fun ensureHeartRateStarted(): HrStartDecision
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
    private var running = false
    private val pending = ArrayDeque<Event>()
    private var fixInFlight = false
    private var lastFixAt: Long? = null

    private val mutableStatus = MutableStateFlow(GymAutomationStatus.OFF)
    val status: StateFlow<GymAutomationStatus> = mutableStatus.asStateFlow()

    fun attach(ctx: Context, h: GymAutomationHost) = onMain {
        host = h
        dispatch(ctx, Event.Reconcile)
        GymGeofenceRegistrar.sync(ctx)
    }

    fun detach(h: GymAutomationHost) = onMain { if (host === h) host = null }

    /** Called by the service when the user (not this coordinator) stops heart rate. */
    fun onHeartRateStopped(ctx: Context) = dispatch(ctx, Event.ManualHrStop)

    /** Called from the gym_mode_enabled listener. */
    fun onGymModeChanged(ctx: Context, enabled: Boolean) {
        if (!writingGym && !enabled) dispatch(ctx, Event.ManualGymOff)
    }

    fun dispatch(ctx: Context, event: Event) = onMain {
        val c = ctx.applicationContext
        appCtx = c
        // Effects can re-enter (preference listeners, service callbacks). Queue them so
        // every event reduces against the journal the previous one persisted.
        if (running) { pending.addLast(event); return@onMain }
        running = true
        try {
            if (!coldStarted) {
                coldStarted = true
                if (event != Event.ColdStart) run(c, Event.ColdStart)
            }
            run(c, event)
            while (pending.isNotEmpty()) run(c, pending.removeFirst())
        } finally {
            running = false
        }
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
            hrOwner = h?.heartRateOwner(),
        )
        val result = GymAutomationReducer.reduce(journal, event, snapshot)
        if (result.journal != journal) GymAutomationRepository.saveJournal(ctx, result.journal)
        if (result.effects.isNotEmpty() || event !is Event.Reconcile) {
            Log.i(TAG, "event=${event.javaClass.simpleName} presence=${result.journal.presence} " +
                "effects=${result.effects.map { it.javaClass.simpleName }} ready=${snapshot.airPodsReady}")
        }
        var journalNow = result.journal
        result.effects.forEach { effect ->
            if (!execute(ctx, effect) && effect is Effect.StartHr && journalNow.ownsHr) {
                // Not started (already running, no selection, permission, rejected): own nothing.
                journalNow = journalNow.copy(ownsHr = false)
                GymAutomationRepository.saveJournal(ctx, journalNow)
            }
        }
        mutableStatus.value = statusOf(rule, journalNow, h)
        maybeRequestPresenceFix(ctx, rule, journalNow, snapshot)
    }

    /** Bounded: one fix in flight, cooldown between fixes, 15 s cap, never a loop. */
    private fun maybeRequestPresenceFix(ctx: Context, rule: GymAutomationRule, j: Journal, s: Snapshot) {
        if (!GymAutomationReducer.needsPresenceFix(j, s) || !rule.hasLocation) return
        if (!GymGeofenceRegistrar.hasPermissions(ctx)) return
        if (!PresenceEvidence.canRequest(s.now, lastFixAt, fixInFlight)) return
        fixInFlight = true
        lastFixAt = s.now
        GymPresenceFix.request(ctx, rule) { presence ->
            onMain {
                fixInFlight = false
                Log.i(TAG, "presence fix=$presence")
                dispatch(ctx, Event.PresenceFix(rule.revision, presence, UUID.randomUUID().toString()))
            }
        }
    }

    /** Returns false only when a StartHr did not leave an automation-owned session running. */
    private fun execute(ctx: Context, effect: Effect): Boolean {
        when (effect) {
            Effect.EnableGym -> writeGym(ctx, true)
            Effect.DisableGym -> writeGym(ctx, false)
            is Effect.StartHr -> {
                val h = host ?: return false
                val decision = h.ensureHeartRateStarted()
                return decision is HrStartDecision.Start && h.heartRateOwner() == HeartRateSource.AUTOMATION
            }
            Effect.StopHr -> host?.stopHeartRateFromAutomation()
            is Effect.ScheduleGraceCheck -> {
                main.removeCallbacks(graceRunnable)
                main.postDelayed(graceRunnable, (effect.atElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
            Effect.CancelGraceCheck -> main.removeCallbacks(graceRunnable)
        }
        return true
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
