package io.automated.ventures.everypods.utils

/** Picks which of the two motion services to request, replacing the old manual toggle.
 *  "Alternate" is service 16, the default; the other is service 14. If the current one never
 *  delivers a sample on a connection, the other is tried once on that connection. The one
 *  that delivers is remembered, so a connection normally starts on a service known to work. */
class HeadMotionServiceChoice(remembered: Boolean = true) {
    var alternate = remembered
        private set
    private var remembered = remembered
    private var switchedOn: Long? = null

    /** The current service produced nothing after all retries. True when the other one should be tried. */
    fun onNoData(generation: Long): Boolean {
        if (switchedOn == generation) return false
        switchedOn = generation
        alternate = !alternate
        return true
    }

    /** A sample arrived on the current service. True when this choice is new and should be saved. */
    fun onData(): Boolean {
        if (remembered == alternate) return false
        remembered = alternate
        return true
    }

    /** A new connection starts from the service that last delivered data. */
    fun onNewConnection(generation: Long) {
        if (switchedOn != null && switchedOn != generation) { alternate = remembered; switchedOn = null }
    }
}
