package io.automated.ventures.everypods.utils

/** Finds reversals relative to the whole excursion, independent of sample rate.
 *  Small steps in a smooth nod must accumulate instead of needing one abrupt step. */
internal class MotionPeakTracker(private val reversalDistance: Double) {
    data class TurningPoint(val index: Int, val value: Double, val isPeak: Boolean)

    private var direction = 0
    private var extreme = Double.NaN
    private var extremeIndex = 0
    private var nextIndex = 0

    @Synchronized fun add(value: Double): TurningPoint? {
        val index = nextIndex++
        if (extreme.isNaN()) {
            extreme = value
            extremeIndex = index
            return null
        }
        if (direction == 0) {
            if (kotlin.math.abs(value - extreme) >= reversalDistance) {
                direction = if (value > extreme) 1 else -1
                extreme = value
                extremeIndex = index
            }
            return null
        }
        if ((direction > 0 && value >= extreme) || (direction < 0 && value <= extreme)) {
            extreme = value
            extremeIndex = index
            return null
        }
        if (kotlin.math.abs(value - extreme) < reversalDistance) return null
        val point = TurningPoint(extremeIndex, extreme, direction > 0)
        direction = -direction
        extreme = value
        extremeIndex = index
        return point
    }

    @Synchronized fun reset() {
        direction = 0
        extreme = Double.NaN
        extremeIndex = 0
        nextIndex = 0
    }
}
