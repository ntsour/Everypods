package io.automated.ventures.everypods.utils

/**
 * Tells whether a wear (ear-detection) report is the first one on the current AACP link.
 *
 * The last wear state is kept across links, so the first report on a new link used to be
 * compared with how the buds were worn when the previous link ended, possibly hours earlier.
 * On 2026-11-23 a Pixel reconnected with "both in" left over, the first report said one in
 * and one out (the second bud reports a moment later), and that read as "took one out":
 * media paused, then played again when the next report said both in. The first report on a
 * link is therefore only a baseline; play / pause follow changes after it.
 */
class WearReportBaseline {
    private var generation: Long = Long.MIN_VALUE

    /** True once per link generation, for its first wear report. */
    @Synchronized
    fun isFirstOnLink(linkGeneration: Long): Boolean {
        if (linkGeneration == generation) return false
        generation = linkGeneration
        return true
    }
}
