package io.automated.ventures.everypods.utils

/** Two complete repetitions, rather than a single abrupt movement.
 *  Head tracking requests 40 ms samples. Sensor indices preserve cadence even
 *  when Bluetooth delivers several reports together in one packet. */
internal object RepeatedMotionPattern {
    const val REQUIRED_EXTREMES = 4
    private const val MIN_GAP_SAMPLES = 3 // 120 ms
    private const val MAX_GAP_SAMPLES = 30 // 1.2 seconds
    private const val MIN_SPAN_SAMPLES = 20 // 0.8 seconds overall

    fun accepts(points: List<Pair<Int, Double>>): Boolean {
        if (points.size < REQUIRED_EXTREMES) return false
        val recent = points.sortedBy { it.first }.takeLast(REQUIRED_EXTREMES)
        if (recent.last().first - recent.first().first < MIN_SPAN_SAMPLES) return false
        return recent.zipWithNext().all { (first, second) ->
            second.first - first.first in MIN_GAP_SAMPLES..MAX_GAP_SAMPLES &&
                (first.second > 0) != (second.second > 0)
        }
    }
}
