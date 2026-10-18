/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HeartRateStatus { STOPPED, WAITING, LIVE, STALE, ERROR }
enum class HeartRateFailure { UNSUPPORTED, NO_CONNECTION, NOT_OWNER, SEND_FAILED, NO_DATA, CONNECTION_LOST, DEVICE_CHANGED }
data class HeartRateSessionState(
    val status: HeartRateStatus = HeartRateStatus.STOPPED,
    val bpm: Int? = null,
    val sampleTimeMillis: Long? = null,
    val failure: HeartRateFailure? = null,
    val serviceId: Int? = null,
) {
    val running get() = status == HeartRateStatus.WAITING || status == HeartRateStatus.LIVE || status == HeartRateStatus.STALE
}

/** Owns session intent independently of any Activity or remembered Compose state. */
class HeartRateSession(
    private val elapsed: () -> Long,
    private val wallClock: () -> Long,
    private val send: (service: Int, intervalMicros: Int) -> Boolean,
) {
    private val mutable = MutableStateFlow(HeartRateSessionState())
    val state = mutable.asStateFlow()
    private var service: Int? = null
    private var lastArrival = 0L
    private var sampled = false

    @Synchronized fun start(supported: Boolean, connected: Boolean, owns: Boolean, firmware: String): Boolean {
        if (mutable.value.running) return true
        val failure = when {
            !supported -> HeartRateFailure.UNSUPPORTED
            !connected -> HeartRateFailure.NO_CONNECTION
            !owns -> HeartRateFailure.NOT_OWNER
            else -> null
        }
        if (failure != null) { mutable.value = HeartRateSessionState(HeartRateStatus.ERROR, failure = failure); return false }
        val selected = HeartRateProtocol.serviceForFirmware(firmware)
        if (!send(selected, 1_000_000)) {
            mutable.value = HeartRateSessionState(HeartRateStatus.ERROR, failure = HeartRateFailure.SEND_FAILED)
            return false
        }
        service = selected
        lastArrival = elapsed()
        sampled = false
        mutable.value = HeartRateSessionState(HeartRateStatus.WAITING, serviceId = selected)
        return true
    }

    @Synchronized fun sample(bpm: Int) {
        if (!mutable.value.running || bpm !in 1..255) return
        lastArrival = elapsed()
        sampled = true
        mutable.value = HeartRateSessionState(HeartRateStatus.LIVE, bpm, wallClock(), serviceId = service)
    }

    @Synchronized fun tick() {
        if (!mutable.value.running) return
        val age = elapsed() - lastArrival
        if (age >= 15_000) stop(HeartRateFailure.NO_DATA)
        else if (sampled && age >= 5_000) mutable.value = mutable.value.copy(status = HeartRateStatus.STALE, bpm = null)
    }

    @Synchronized fun stop(failure: HeartRateFailure? = null, connectionUsable: Boolean = true) {
        val activeService = service
        service = null
        val sent = activeService == null || !connectionUsable || send(activeService, 0)
        mutable.value = HeartRateSessionState(
            status = if (failure != null || !sent) HeartRateStatus.ERROR else HeartRateStatus.STOPPED,
            failure = failure ?: if (!sent) HeartRateFailure.SEND_FAILED else null,
        )
        sampled = false
    }
}
