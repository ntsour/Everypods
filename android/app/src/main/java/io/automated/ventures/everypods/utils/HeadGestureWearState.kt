package io.automated.ventures.everypods.utils

/** AACP wear evidence is tied to one installed socket, independently of its handshake state. */
class HeadGestureWearState {
    var reportGeneration: Long? = null
        private set
    var wornMask: Int = 0
        private set
    var revision: Long = 0
        private set
    private var queryGeneration = -1L
    private var queryCount = 0
    private var lastQueryAt: Long? = null

    fun report(generation: Long, currentGeneration: Long, socketInstalled: Boolean, worn: Boolean) =
        report(generation, currentGeneration, socketInstalled, if (worn) 1 else 0)

    fun report(generation: Long, currentGeneration: Long, socketInstalled: Boolean, wornMask: Int) {
        if (!socketInstalled || generation != currentGeneration || wornMask !in 0..3) return
        if (reportGeneration != generation || this.wornMask != wornMask) revision++
        reportGeneration = generation
        this.wornMask = wornMask
        queryCount = 0
    }

    fun reconcile(currentGeneration: Long, socketInstalled: Boolean) {
        if (!socketInstalled || reportGeneration != currentGeneration) {
            if (reportGeneration != null) revision++
            reportGeneration = null
            wornMask = 0
        }
        if (queryGeneration != currentGeneration || !socketInstalled) {
            queryGeneration = currentGeneration
            queryCount = 0
            lastQueryAt = null
        }
    }

    fun isKnown(currentGeneration: Long) = reportGeneration == currentGeneration
    fun isWorn(currentGeneration: Long) = isKnown(currentGeneration) && wornMask != 0

    /** Missing AACP wear reports get three bounded refreshes. BLE is a refresh hint, never authority. */
    fun shouldRefresh(currentGeneration: Long, now: Long, needed: Boolean, local: Boolean, hint: Boolean = false): Boolean {
        if (!needed || !local) {
            queryCount = 0; lastQueryAt = null
            return false
        }
        if (queryGeneration != currentGeneration) {
            queryGeneration = currentGeneration; queryCount = 0; lastQueryAt = null
        }
        if (isKnown(currentGeneration) && !hint) return false
        if (!hint && queryCount >= 3) return false
        val interval = if (hint) 1000L else 2000L * (queryCount + 1)
        if (lastQueryAt?.let { now - it < interval } == true) return false
        lastQueryAt = now
        queryCount++
        return true
    }
}
