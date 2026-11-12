/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
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
    fun heartRateReady(): Boolean
    fun heartRateLive(): Boolean
    fun heartRateActive(): Boolean
    fun gymAnnouncementReady(): Boolean
    fun announceGymEnabled()
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
    private var lastAirPodsReady = false
    private var presenceGeneration = 0L
    private var exitRecoveryPending = false
    private var lastReadinessLog: String? = null

    private val mutableStatus = MutableStateFlow(GymAutomationStatus.OFF)
    val status: StateFlow<GymAutomationStatus> = mutableStatus.asStateFlow()

    fun attach(ctx: Context, h: GymAutomationHost) = onMain {
        host = h
        GymAutomationDiagnostics.record(ctx, "host attached ${GymAutomationDiagnostics.environment(ctx)}")
        dispatch(ctx, Event.Reconcile)
    }

    fun detach(h: GymAutomationHost) = onMain {
        if (host === h) {
            appCtx?.let { GymAutomationDiagnostics.record(it, "host detached") }
            host = null; lastAirPodsReady = false; lastReadinessLog = null
        }
    }

    /** Called by the service when the user (not this coordinator) stops heart rate. */
    fun onHeartRateStopped(ctx: Context) = dispatch(ctx, Event.ManualHrStop)

    /** Called by GymModePrefs for a manual change, including while the service is stopped. */
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
            zoneRevisions = rule.zoneRevisions,
            heartRateReady = h?.heartRateReady() == true,
            heartRateLive = h?.heartRateLive() == true,
            gymAnnouncementReady = h?.gymAnnouncementReady() == true,
        )
        val stale = GymAutomationReducer.isOlderEvidence(journal, event, snapshot)
        if (stale) GymAutomationDiagnostics.record(ctx, "event ignored reason=older_evidence type=${event.javaClass.simpleName}")
        val result = GymAutomationReducer.reduce(journal, event, snapshot)
        val recoverExit = event is Event.Exited && !stale && rule.enabled &&
            snapshot.isCurrent(event.zoneId, event.revision) &&
            result.journal.presence == Presence.UNKNOWN && result.journal.visitId != null
        if (event == Event.RuleChanged || event == Event.LocationUnavailable || recoverExit ||
            ((event is Event.Arrived || event is Event.Exited) && result.journal != journal)) {
            presenceGeneration++
        }
        if (event == Event.RuleChanged || journal.visitId != null && result.journal.visitId != journal.visitId) {
            GymAutomationJobs.cancelDeparture(ctx)
            exitRecoveryPending = false
        }
        val readiness = "host=${h != null} airpods=${snapshot.airPodsReady} hrReady=${snapshot.heartRateReady} hrActive=${snapshot.hrActive} hrLive=${snapshot.heartRateLive} announcementReady=${snapshot.gymAnnouncementReady}"
        if (readiness != lastReadinessLog) {
            lastReadinessLog = readiness
            GymAutomationDiagnostics.record(ctx, "readiness $readiness")
        }
        if (event == Event.GraceCheck && journal.departureDeadline != null && result.journal.departureDeadline == null) {
            main.removeCallbacks(graceRunnable)
            ctx.getSystemService(AlarmManager::class.java).cancel(graceIntent(ctx))
        }
        if (result.journal != journal) GymAutomationRepository.saveJournal(ctx, result.journal)
        if (result.effects.isNotEmpty() || event !is Event.Reconcile) {
            GymAutomationDiagnostics.record(ctx, "event=${event.javaClass.simpleName} presence=${result.journal.presence} " +
                "zonesInside=${result.journal.insideZones.size} effects=${result.effects.map { it.javaClass.simpleName }} " +
                "enabled=${rule.enabled} ready=${snapshot.airPodsReady} hrReady=${snapshot.heartRateReady} " +
                "gym=${snapshot.gymOn} previousPresence=${journal.presence} previousZones=${journal.insideZones.size} " +
                "ownsGym=${result.journal.ownsGym} ownsHr=${result.journal.ownsHr} pendingAnnouncement=${result.journal.gymAnnouncementPending} " +
                "suppressed=${result.journal.gymSuppressed} locationUnavailable=${result.journal.locationUnavailable} hrAttempts=${result.journal.hrStartAttempts}")
        }
        var journalNow = result.journal
        result.effects.forEach { effect ->
            val ok = try { execute(ctx, effect) } catch (e: Exception) {
                GymAutomationDiagnostics.record(ctx, "effect failed type=${effect.javaClass.simpleName} error=${e.javaClass.simpleName}")
                false
            }
            GymAutomationDiagnostics.record(ctx, "effect executed type=${effect.javaClass.simpleName} accepted=$ok")
            if (!ok && effect is Effect.StartHr && journalNow.ownsHr) {
                // Not started (already running, no selection, permission, rejected): own nothing.
                journalNow = journalNow.copy(ownsHr = false)
                GymAutomationRepository.saveJournal(ctx, journalNow)
            }
        }
        mutableStatus.value = statusOf(rule, journalNow, h)
        val connectedNow = snapshot.airPodsReady && !lastAirPodsReady
        lastAirPodsReady = snapshot.airPodsReady
        // UNKNOWN retains recovery; OUTSIDE is rechecked on connection or foreground entry,
        // not on every wear, battery, or heart-rate reconciliation.
        val explicitCheck = connectedNow || event == Event.ForegroundCheck ||
            (event == Event.RuleChanged && journalNow.presence == Presence.UNKNOWN)
        if (recoverExit) {
            if (fixInFlight) exitRecoveryPending = true
            else requestPresenceFix(ctx, rule, "exit_recovery", bypassCooldown = true)
        } else if ((event == Event.ColdStart && journalNow.presence == Presence.UNKNOWN) || explicitCheck) {
            requestPresenceFix(ctx, rule, if (connectedNow) "connection" else event.javaClass.simpleName,
                explicitCheck = explicitCheck)
        }
    }

    fun checkDeparture(ctx: Context, visit: String?, revision: Long, done: () -> Unit) = onMain {
        val rule = GymAutomationRepository.rule(ctx)
        val journal = GymAutomationRepository.journal(ctx)
        if (visit == null || journal.visitId != visit || rule.revision != revision ||
            !rule.enabled || !rule.restoreOnExit || !journal.ownsGym && !journal.ownsHr) {
            GymAutomationDiagnostics.record(ctx, "departure check skipped reason=visit_changed")
            done()
        } else {
            requestPresenceFix(ctx, rule, "departure_fallback", bypassCooldown = true, expectedVisit = visit, done = done)
        }
    }

    private fun requestPresenceFix(
        ctx: Context, rule: GymAutomationRule, source: String, explicitCheck: Boolean = false,
        bypassCooldown: Boolean = false, expectedVisit: String? = null, done: () -> Unit = {},
    ) {
        val now = SystemClock.elapsedRealtime()
        val skip = when {
            !rule.enabled || !rule.hasLocation -> "rule_off"
            !GymGeofenceRegistrar.hasPermissions(ctx) -> "permission"
            fixInFlight -> "in_flight"
            !bypassCooldown && !PresenceEvidence.canRequest(now, lastFixAt, false, explicitCheck) -> "cooldown"
            else -> null
        }
        if (skip != null) {
            GymAutomationDiagnostics.record(ctx, "fix skipped source=$source reason=$skip")
            done()
            return
        }
        fixInFlight = true
        lastFixAt = now
        val generation = presenceGeneration
        GymAutomationDiagnostics.record(ctx, "fix requested source=$source")
        GymPresenceFix.request(ctx, rule) { reading, observedAt ->
            onMain {
                fixInFlight = false
                try {
                    val journal = GymAutomationRepository.journal(ctx)
                    if (generation != presenceGeneration || expectedVisit != null && journal.visitId != expectedVisit) {
                        GymAutomationDiagnostics.record(ctx, "fix discarded source=$source reason=newer_transition")
                    } else {
                        GymAutomationDiagnostics.record(ctx, "fix result=${reading.presence} source=$source zones=${reading.inside.size}")
                        dispatch(ctx, Event.PresenceFix(rule.revision, reading.presence, UUID.randomUUID().toString(),
                            reading.inside, observedAt, reading.outside))
                    }
                    if (exitRecoveryPending) {
                        exitRecoveryPending = false
                        if (GymAutomationRepository.journal(ctx).presence == Presence.UNKNOWN) {
                            requestPresenceFix(ctx, GymAutomationRepository.rule(ctx), "exit_recovery", bypassCooldown = true)
                        }
                    }
                } finally {
                    done()
                }
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
                GymAutomationDiagnostics.record(ctx, "heart_rate decision=${decision.javaClass.simpleName} automationOwner=${h.heartRateOwner() == HeartRateSource.AUTOMATION}")
                return decision is HrStartDecision.Start && h.heartRateOwner() == HeartRateSource.AUTOMATION
            }
            Effect.StopHr -> host?.stopHeartRateFromAutomation()
            Effect.AnnounceGym -> host?.announceGymEnabled()
            Effect.CancelGraceCheck -> {
                main.removeCallbacks(graceRunnable)
                ctx.getSystemService(AlarmManager::class.java).cancel(graceIntent(ctx))
            }
        }
        return true
    }

    private fun graceIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4712, Intent(ctx, GymGeofenceReceiver::class.java).setAction(GymGeofenceReceiver.ACTION_GRACE_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        rule.hrActionEnabled && !j.hrSuppressed && h?.heartRateActive() != true &&
            HeartRateSharingPrefs.configuredMethod(appCtx ?: return GymAutomationStatus.OFF) != null &&
            h?.heartRateReady() != true -> GymAutomationStatus.WAITING_AIRPODS
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
