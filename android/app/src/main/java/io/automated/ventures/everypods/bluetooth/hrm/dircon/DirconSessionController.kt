package io.automated.ventures.everypods.bluetooth.hrm.dircon

import android.content.Context
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateShareIntent
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateTransportPhase
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Owns one requested DIRCON session across replaceable listener generations. */
class DirconSessionController(
    context: Context,
    private val scope: CoroutineScope,
    private val changed: (HeartRateSharingState) -> Unit,
    private val failedPermanently: (SharingError) -> Unit,
    private val jitter: () -> Double = { Random.nextDouble(0.8, 1.2000001) },
    private val retryDelayMs: ((Int) -> Long)? = null,
    private val createBroadcaster: (Context, (HeartRateSharingState) -> Unit, () -> Unit) -> DirconTransport =
        { app, onChanged, onFailed -> HeartRateBroadcaster(app, onChanged, onFailed) },
    private val elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val createNetworkMonitor: (Context) -> DirconNetworkMonitor = { DirconNetworkMonitor(it) },
) {
    private val app = context.applicationContext
    private val networkMonitor = createNetworkMonitor(app)
    private var generation = 0L
    private var failures = 0
    private var retry: Job? = null
    private var nextRetryAtElapsedMs: Long? = null
    private var broadcaster: DirconTransport? = null
    private var availabilityWatch: java.io.Closeable? = null
    private var stopped = true

    @Synchronized fun start() {
        if (!stopped) return
        stopped = false
        generation++
        failures = 0
        val expected = generation
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "session_start", mapOf(
            "generation" to expected, "phase" to HeartRateTransportPhase.STARTING.name,
        ))
        availabilityWatch = networkMonitor.watchWifiAvailability {
            scope.launch {
                synchronized(this@DirconSessionController) {
                    if (!stopped && generation == expected && broadcaster == null &&
                        networkMonitor.currentEndpoint() != null) {
                        retry?.cancel()
                        retry = null
                        attempt(expected)
                    }
                }
            }
        }
        attempt(generation)
    }

    @Synchronized fun publish(bpm: Int) { broadcaster?.publish(bpm) }

    @Synchronized fun clearReading() { broadcaster?.clearReading() }

    @Synchronized fun snapshot(): HeartRateSharingState {
        if (stopped) return HeartRateSharingState(transportPhase = HeartRateTransportPhase.STOPPED)
        val current = broadcaster?.snapshot()
        val phase = when {
            current?.advertised == true -> HeartRateTransportPhase.DISCOVERABLE
            networkMonitor.currentEndpoint() == null -> waitingPhase()
            current?.active == true -> HeartRateTransportPhase.STARTING
            else -> HeartRateTransportPhase.RETRYING
        }
        return (current ?: HeartRateSharingState(active = true, recovering = true)).copy(
            active = true,
            recovering = current?.advertised != true && (current?.recovering == true || failures > 0 || current == null),
            intent = HeartRateShareIntent.REQUESTED,
            transportPhase = phase,
            nextRetryAtElapsedMs = nextRetryAtElapsedMs,
        )
    }

    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        generation++
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "session_stop", mapOf(
            "generation" to generation, "phase" to HeartRateTransportPhase.STOPPED.name,
        ))
        retry?.cancel()
        retry = null
        nextRetryAtElapsedMs = null
        broadcaster?.stop()
        broadcaster = null
        availabilityWatch?.close()
        availabilityWatch = null
        networkMonitor.close()
    }

    @Synchronized private fun attempt(expectedGeneration: Long) {
        if (stopped || generation != expectedGeneration) return
        nextRetryAtElapsedMs = null
        var wasAdvertised = false
        lateinit var current: DirconTransport
        current = createBroadcaster(app, { state ->
            synchronized(this) {
                if (!stopped && generation == expectedGeneration && broadcaster === current) {
                    if (state.advertised) failures = 0
                    val recovering = failures > 0 && !state.advertised
                    if (state.advertised && !wasAdvertised) {
                        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "discoverable", mapOf(
                            "generation" to expectedGeneration,
                            "clients" to state.clients,
                            "subscriptions" to state.subscriptions,
                        ))
                    }
                    wasAdvertised = state.advertised
                    changed(state.copy(
                        recovering = recovering,
                        intent = HeartRateShareIntent.REQUESTED,
                        transportPhase = when {
                            state.advertised -> HeartRateTransportPhase.DISCOVERABLE
                            recovering -> waitingPhase()
                            else -> HeartRateTransportPhase.STARTING
                        },
                    ))
                }
            }
        }, {
            handleFailure(expectedGeneration, current)
        })
        broadcaster = current
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "transport_attempt", mapOf(
            "generation" to expectedGeneration, "attempt" to (failures + 1),
            "phase" to HeartRateTransportPhase.STARTING.name,
        ))
        changed(HeartRateSharingState(
            active = true,
            recovering = failures > 0,
            intent = HeartRateShareIntent.REQUESTED,
            transportPhase = HeartRateTransportPhase.STARTING,
        ))
        current.start()
    }

    @Synchronized private fun handleFailure(expectedGeneration: Long, failed: DirconTransport) {
        if (stopped || generation != expectedGeneration || broadcaster !== failed) return
        val error = failed.snapshot().error ?: SharingError.SERVER
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "transport_failure", mapOf(
            "error" to error.name, "attempt" to (failures + 1), "generation" to expectedGeneration,
        ))
        failed.stop(error)
        broadcaster = null
        if (error !in RETRYABLE) {
            stopped = true
            generation++
            retry?.cancel()
            retry = null
            availabilityWatch?.close()
            availabilityWatch = null
            networkMonitor.close()
            failedPermanently(error)
            return
        }
        failures++
        val scheduledDelay = retryDelayMs?.invoke(failures) ?: DirconRetryPolicy.delayMs(failures, jitter())
        val delayMs = scheduledDelay
        nextRetryAtElapsedMs = elapsedRealtime() + delayMs
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "retry_scheduled", mapOf(
            "error" to error.name, "attempt" to failures, "retryMs" to delayMs,
            "retryAtElapsedMs" to nextRetryAtElapsedMs, "generation" to expectedGeneration,
            "phase" to waitingPhase().name,
        ))
        changed(HeartRateSharingState(
            active = true,
            recovering = true,
            error = error,
            intent = HeartRateShareIntent.REQUESTED,
            transportPhase = if (error == SharingError.UNSUPPORTED_NETWORK) HeartRateTransportPhase.ACTION_REQUIRED else waitingPhase(),
            nextRetryAtElapsedMs = nextRetryAtElapsedMs,
        ))
        retry?.cancel()
        retry = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            synchronized(this@DirconSessionController) {
                if (!stopped && generation == expectedGeneration) attempt(expectedGeneration)
            }
        }
    }

    companion object {
        private val RETRYABLE = setOf(
            SharingError.WIFI_REQUIRED, SharingError.UNSUPPORTED_NETWORK, SharingError.NETWORK_LOST,
            SharingError.ADVERTISEMENT, SharingError.SERVER,
        )
    }

    @Synchronized private fun waitingPhase(): HeartRateTransportPhase = when {
        networkMonitor.currentEndpoint() == null && networkMonitor.hasWifiNetwork() -> HeartRateTransportPhase.ACTION_REQUIRED
        networkMonitor.currentEndpoint() == null -> HeartRateTransportPhase.WAITING_NETWORK
        else -> HeartRateTransportPhase.RETRYING
    }
}
