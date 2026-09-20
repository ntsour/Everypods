/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.bluetooth

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Relative room-level proximity band. Not meters / not a floor map. */
enum class RoomBand {
    RIGHT_HERE,
    SAME_ROOM,
    NEXT_ROOM,
    FARTHER,
    SEARCHING,
}

/** Short-horizon warmer/colder trend from smoothed RSSI. */
enum class SignalTrend {
    CLOSER,
    FARTHER,
    STABLE,
}

data class SignalSnapshot(
    val smoothedRssi: Float,
    val band: RoomBand,
    val trend: SignalTrend,
    val score: Int,
    val pathLossDb: Float?,
)

/**
 * Tunable room-band + RSSI pipeline constants (single place).
 *
 * Bands are relative while walking — never claim absolute meters.
 */
object ProximityBands {
    const val WINDOW_SIZE = 6
    const val EMA_ALPHA = 0.40f
    const val SPIKE_REJECT_DB = 12f
    /** Keep enough history for the farthest (longest) trend window. */
    const val TREND_HISTORY_MAX_MS = 4_200L
    // Near: larger dB steps per meter — need higher threshold to avoid noise flips.
    const val TREND_THRESHOLD_NEAR_DB = 1.3f
    const val TREND_THRESHOLD_NEXT_DB = 0.8f
    // Far: smaller dB steps while walking — lower threshold + longer window.
    const val TREND_THRESHOLD_FAR_DB = 0.6f
    const val TREND_WINDOW_NEAR_MS = 2_800L
    const val TREND_WINDOW_NEXT_MS = 3_600L
    const val TREND_WINDOW_FAR_MS = 4_200L

    fun trendThresholdDb(band: RoomBand): Float = when (band) {
        RoomBand.RIGHT_HERE, RoomBand.SAME_ROOM -> TREND_THRESHOLD_NEAR_DB
        RoomBand.NEXT_ROOM -> TREND_THRESHOLD_NEXT_DB
        RoomBand.FARTHER, RoomBand.SEARCHING -> TREND_THRESHOLD_FAR_DB
    }

    fun trendWindowMs(band: RoomBand): Long = when (band) {
        RoomBand.RIGHT_HERE, RoomBand.SAME_ROOM -> TREND_WINDOW_NEAR_MS
        RoomBand.NEXT_ROOM -> TREND_WINDOW_NEXT_MS
        RoomBand.FARTHER, RoomBand.SEARCHING -> TREND_WINDOW_FAR_MS
    }
    const val HYSTERESIS_DB = 3f

    /** Default RIGHT_HERE enter threshold (dBm). Personal floor may raise this. */
    // Apartment peaks ~-44; enter slightly below peak.
    const val RIGHT_HERE_ENTER = -50f
    const val RIGHT_HERE_EXIT_DELTA = 3f // leave when drop below enter - 3 → -53

    // Nikos apartment calibration: leave Same room ~-56; Farther beyond ~-85.
    const val SAME_ROOM_LOW = -56f
    const val NEXT_ROOM_LOW = -85f

    /** Coarse path-loss helpers (txPower - smoothedRssi). Secondary to RSSI. */
    const val PATH_LOSS_NEAR = 45f
    const val PATH_LOSS_FAR = 70f

    fun rightHereExit(enter: Float = RIGHT_HERE_ENTER): Float = enter - RIGHT_HERE_EXIT_DELTA

    /**
     * Map RSSI onto the radar / signal bar.
     *
     * Old range (-100…-45) wasted most of the dial on outdoor distances — an
     * apartment corner-to-corner walk only moved ~half the radar. Use a tighter
     * home-scale window and a mild curve so small walks read more clearly.
     */
    // Observed apartment span ~-44..-88; map that onto the full radar.
    const val SCORE_RSSI_NEAR = -44f
    const val SCORE_RSSI_FAR = -88f

    fun scoreFromRssi(rssi: Float): Int {
        val near = SCORE_RSSI_NEAR
        val far = SCORE_RSSI_FAR
        val span = near - far // 35 dB
        val clamped = rssi.coerceIn(far, near)
        val linear = ((clamped - far) / span).coerceIn(0f, 1f)
        // pow < 1 expands the farther half so mid/far walks move the dial more.
        val shaped = linear.toDouble().pow(0.72).toFloat()
        return (shaped * 100f).roundToInt().coerceIn(0, 100)
    }

    fun pulseIntervalMs(band: RoomBand): Long = when (band) {
        RoomBand.RIGHT_HERE -> 300L
        RoomBand.SAME_ROOM -> 550L
        RoomBand.NEXT_ROOM -> 900L
        RoomBand.FARTHER -> 1_400L
        RoomBand.SEARCHING -> Long.MAX_VALUE
    }
}

/**
 * Per-device tracker: median window → light EMA, spike reject, hysteresis bands, trend.
 */
class DeviceSignalTracker(
    private var rightHereEnter: Float = ProximityBands.RIGHT_HERE_ENTER,
) {
    private val rawWindow = ArrayDeque<Int>(ProximityBands.WINDOW_SIZE)
    private var smoothed: Float? = null
    private var band: RoomBand = RoomBand.SEARCHING
    private var pendingSpike: Int? = null
    private var justConfirmedSpike: Boolean = false
    private val smoothedHistory = ArrayDeque<Pair<Long, Float>>()

    fun setRightHereEnter(enter: Float) {
        rightHereEnter = enter
    }

    fun reset() {
        rawWindow.clear()
        smoothed = null
        band = RoomBand.SEARCHING
        pendingSpike = null
        justConfirmedSpike = false
        smoothedHistory.clear()
    }

    fun push(rawRssi: Int, txPower: Int?, nowMs: Long): SignalSnapshot {
        val accepted = acceptSample(rawRssi)
        if (accepted != null) {
            if (rawWindow.size >= ProximityBands.WINDOW_SIZE) {
                rawWindow.removeFirst()
            }
            rawWindow.addLast(accepted)
            // After a confirmed spike, re-seed the window so median isn't stuck on pre-spike values.
            if (justConfirmedSpike) {
                rawWindow.clear()
                repeat(minOf(3, ProximityBands.WINDOW_SIZE)) { rawWindow.addLast(accepted) }
                justConfirmedSpike = false
            }
        }

        val median = medianOf(rawWindow) ?: rawRssi.toFloat()
        val previous = smoothed
        val nextSmoothed = if (previous == null) {
            median
        } else {
            (ProximityBands.EMA_ALPHA * median) + ((1f - ProximityBands.EMA_ALPHA) * previous)
        }
        smoothed = nextSmoothed

        smoothedHistory.addLast(nowMs to nextSmoothed)
        while (smoothedHistory.isNotEmpty() &&
            nowMs - smoothedHistory.first().first > ProximityBands.TREND_HISTORY_MAX_MS
        ) {
            smoothedHistory.removeFirst()
        }

        val pathLoss = txPower?.let { it.toFloat() - nextSmoothed }
        band = updateBand(band, nextSmoothed, pathLoss)
        val trend = classifyTrend()
        return SignalSnapshot(
            smoothedRssi = nextSmoothed,
            band = band,
            trend = trend,
            score = ProximityBands.scoreFromRssi(nextSmoothed),
            pathLossDb = pathLoss,
        )
    }

    /** Mark searching when device goes stale / no recent samples. */
    fun markSearching(): SignalSnapshot {
        band = RoomBand.SEARCHING
        val rssi = smoothed ?: -100f
        return SignalSnapshot(
            smoothedRssi = rssi,
            band = RoomBand.SEARCHING,
            trend = SignalTrend.STABLE,
            score = 0,
            pathLossDb = null,
        )
    }

    private fun acceptSample(rawRssi: Int): Int? {
        val current = smoothed
        if (current == null) {
            pendingSpike = null
            return rawRssi
        }
        val delta = abs(rawRssi.toFloat() - current)
        if (delta <= ProximityBands.SPIKE_REJECT_DB) {
            pendingSpike = null
            return rawRssi
        }
        val pending = pendingSpike
        if (pending != null &&
            sameDirection(pending, rawRssi, current) &&
            abs(rawRssi.toFloat() - current) > ProximityBands.SPIKE_REJECT_DB
        ) {
            pendingSpike = null
            justConfirmedSpike = true
            return rawRssi
        }
        pendingSpike = rawRssi
        return null
    }

    private fun sameDirection(a: Int, b: Int, baseline: Float): Boolean {
        val da = a - baseline
        val db = b - baseline
        return (da > 0f && db > 0f) || (da < 0f && db < 0f)
    }

    private fun classifyTrend(): SignalTrend {
        if (smoothedHistory.size < 2) return SignalTrend.STABLE
        val newestTs = smoothedHistory.last().first
        val newest = smoothedHistory.last().second
        val windowMs = ProximityBands.trendWindowMs(band)
        val threshold = ProximityBands.trendThresholdDb(band)
        // Oldest sample within this band's lookback window.
        val oldestInWindow = smoothedHistory.firstOrNull { newestTs - it.first <= windowMs }
            ?: smoothedHistory.first()
        val fullDelta = newest - oldestInWindow.second
        val halfMs = windowMs / 2
        val midSample = smoothedHistory.lastOrNull { newestTs - it.first >= halfMs }
            ?: oldestInWindow
        val recentDelta = newest - midSample.second
        val delta = if (kotlin.math.abs(recentDelta) >= kotlin.math.abs(fullDelta)) {
            recentDelta
        } else {
            fullDelta
        }
        return when {
            delta >= threshold -> SignalTrend.CLOSER
            delta <= -threshold -> SignalTrend.FARTHER
            else -> SignalTrend.STABLE
        }
    }

    companion object {
        fun medianOf(values: Collection<Int>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2f
            } else {
                sorted[mid].toFloat()
            }
        }

        fun classifyRaw(rssi: Float, rightHereEnter: Float = ProximityBands.RIGHT_HERE_ENTER): RoomBand {
            return when {
                rssi >= rightHereEnter -> RoomBand.RIGHT_HERE
                rssi >= ProximityBands.SAME_ROOM_LOW -> RoomBand.SAME_ROOM
                rssi >= ProximityBands.NEXT_ROOM_LOW -> RoomBand.NEXT_ROOM
                else -> RoomBand.FARTHER
            }
        }

        fun updateBand(
            current: RoomBand,
            rssi: Float,
            pathLossDb: Float?,
            rightHereEnter: Float = ProximityBands.RIGHT_HERE_ENTER,
        ): RoomBand {
            val hyst = ProximityBands.HYSTERESIS_DB
            val rightHereExit = ProximityBands.rightHereExit(rightHereEnter)
            val sameRoomExitLow = ProximityBands.SAME_ROOM_LOW - hyst // -62
            val sameRoomEnterFromNext = ProximityBands.SAME_ROOM_LOW + hyst // -56
            val nextExitLow = ProximityBands.NEXT_ROOM_LOW - hyst // -78
            val nextEnterFromFar = ProximityBands.NEXT_ROOM_LOW + hyst // -72

            var next = when (current) {
                RoomBand.SEARCHING -> classifyRaw(rssi, rightHereEnter)
                RoomBand.RIGHT_HERE -> {
                    if (rssi < rightHereExit) {
                        classifyRaw(rssi, rightHereEnter)
                    } else {
                        RoomBand.RIGHT_HERE
                    }
                }
                RoomBand.SAME_ROOM -> when {
                    rssi >= rightHereEnter -> RoomBand.RIGHT_HERE
                    rssi < sameRoomExitLow -> classifyRaw(rssi, rightHereEnter)
                    else -> RoomBand.SAME_ROOM
                }
                RoomBand.NEXT_ROOM -> when {
                    rssi >= sameRoomEnterFromNext -> RoomBand.SAME_ROOM
                    rssi < nextExitLow -> RoomBand.FARTHER
                    else -> RoomBand.NEXT_ROOM
                }
                RoomBand.FARTHER -> {
                    if (rssi >= nextEnterFromFar) RoomBand.NEXT_ROOM else RoomBand.FARTHER
                }
            }

            // Secondary path-loss nudge only near edges.
            if (pathLossDb != null) {
                next = nudgeWithPathLoss(next, rssi, pathLossDb, rightHereEnter, hyst)
            }
            return next
        }

        private fun nudgeWithPathLoss(
            band: RoomBand,
            rssi: Float,
            pathLossDb: Float,
            rightHereEnter: Float,
            hyst: Float,
        ): RoomBand {
            val nearEdge = nearAnyEdge(rssi, rightHereEnter, hyst)
            if (!nearEdge) return band
            return when {
                pathLossDb <= ProximityBands.PATH_LOSS_NEAR -> when (band) {
                    RoomBand.SAME_ROOM -> RoomBand.RIGHT_HERE
                    RoomBand.NEXT_ROOM -> RoomBand.SAME_ROOM
                    RoomBand.FARTHER -> RoomBand.NEXT_ROOM
                    else -> band
                }
                pathLossDb >= ProximityBands.PATH_LOSS_FAR -> when (band) {
                    RoomBand.RIGHT_HERE -> RoomBand.SAME_ROOM
                    RoomBand.SAME_ROOM -> RoomBand.NEXT_ROOM
                    RoomBand.NEXT_ROOM -> RoomBand.FARTHER
                    else -> band
                }
                else -> band
            }
        }

        private fun nearAnyEdge(rssi: Float, rightHereEnter: Float, hyst: Float): Boolean {
            val edges = listOf(
                rightHereEnter,
                ProximityBands.SAME_ROOM_LOW,
                ProximityBands.NEXT_ROOM_LOW,
            )
            return edges.any { abs(rssi - it) <= hyst }
        }
    }

    private fun updateBand(
        current: RoomBand,
        rssi: Float,
        pathLossDb: Float?,
    ): RoomBand = Companion.updateBand(current, rssi, pathLossDb, rightHereEnter)
}
