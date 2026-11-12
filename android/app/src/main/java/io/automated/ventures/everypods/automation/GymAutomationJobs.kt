/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** One-shot work that Android can run even after the AirPods service has stopped. */
class GymAutomationJobs : JobService() {
    private val active = mutableMapOf<Int, JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        active[params.jobId] = params
        if (params.jobId != REGISTRATION && params.jobId != DEPARTURE) {
            active.remove(params.jobId)
            return false
        }
        Handler(Looper.getMainLooper()).post {
            if (active[params.jobId] !== params) return@post
            when (params.jobId) {
                REGISTRATION -> GymGeofenceRegistrar.sync(this, retry = true) { complete(params) }
                DEPARTURE -> GymAutomationCoordinator.checkDeparture(
                    this, params.extras.getString(VISIT), params.extras.getLong(REVISION)
                ) { complete(params) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active[params.jobId] === params) active.remove(params.jobId)
        GymAutomationDiagnostics.record(this, "job stopped id=${params.jobId} reason=${params.stopReason}")
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    private fun complete(params: JobParameters) {
        if (active[params.jobId] === params) {
            active.remove(params.jobId)
            jobFinished(params, false)
        }
    }

    companion object {
        private const val REGISTRATION = 4713
        private const val DEPARTURE = 4714
        private const val VISIT = "visit"
        private const val REVISION = "revision"
        private const val DEPARTURE_DELAY_MS = 4 * 60_000L

        fun retryRegistration(ctx: Context, attempt: Int) =
            schedule(ctx, REGISTRATION, 30_000L shl attempt)

        fun cancelRegistrationRetry(ctx: Context) = scheduler(ctx).cancel(REGISTRATION)

        fun scheduleDeparture(ctx: Context, source: String) {
            val rule = GymAutomationRepository.rule(ctx)
            val journal = GymAutomationRepository.journal(ctx)
            val visit = journal.visitId ?: return
            if (!rule.enabled || !rule.restoreOnExit || !journal.ownsGym && !journal.ownsHr ||
                !GymGeofenceRegistrar.hasPermissions(ctx)) return
            val pending = scheduler(ctx).getPendingJob(DEPARTURE)
            if (pending?.extras?.getString(VISIT) == visit &&
                pending.extras.getLong(REVISION) == rule.revision) return
            schedule(ctx, DEPARTURE, DEPARTURE_DELAY_MS, PersistableBundle().apply {
                putString(VISIT, visit)
                putLong(REVISION, rule.revision)
            }, source)
        }

        fun cancelDeparture(ctx: Context) = scheduler(ctx).cancel(DEPARTURE)

        private fun scheduler(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

        private fun schedule(ctx: Context, id: Int, delayMs: Long, extras: PersistableBundle = PersistableBundle(),
                             source: String = "registration") {
            runCatching {
                val job = JobInfo.Builder(id, ComponentName(ctx, GymAutomationJobs::class.java))
                    .setMinimumLatency(delayMs)
                    .setExtras(extras)
                    .build()
                val accepted = scheduler(ctx).schedule(job) == JobScheduler.RESULT_SUCCESS
                GymAutomationDiagnostics.record(ctx, "job scheduled source=$source delayMs=$delayMs accepted=$accepted")
            }.onFailure {
                GymAutomationDiagnostics.record(ctx, "job schedule_failed source=$source error=${it.javaClass.simpleName}")
            }
        }
    }
}
