/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

import io.automated.ventures.everypods.data.BatteryComponent
import io.automated.ventures.everypods.data.BatteryStatus

/** Only current AACP battery packets are evidence; BLE flags and lid events are not. */
class HeartRateCaseStorage(private val elapsed: () -> Long) {
    private var generation: Long? = null
    private var reportedAt = 0L
    private var stored = false

    /** Null means ignored evidence; false is a valid report that the buds are not both stored. */
    fun report(packet: ByteArray, sourceGeneration: Long, arrivedAt: Long, currentGeneration: Long): Boolean? {
        if (sourceGeneration != currentGeneration || !HeartRateContinuity.fresh(arrivedAt, elapsed()) ||
            (generation == sourceGeneration && arrivedAt < reportedAt)) return null
        if (packet.size != 22 || !packet.take(6).toByteArray().contentEquals(byteArrayOf(4, 0, 4, 0, 4, 0))) return null
        val buds = listOf(7, 12).associate { packet[it].toInt() to packet[it + 3].toInt() }
        generation = sourceGeneration
        reportedAt = arrivedAt
        stored = listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT).all {
            buds[it] == BatteryStatus.CHARGING || buds[it] == BatteryStatus.OPTIMIZED_CHARGING
        }
        return stored
    }

    fun confirmed(currentGeneration: Long): Boolean = stored && generation == currentGeneration &&
        HeartRateContinuity.fresh(reportedAt, elapsed())
}
