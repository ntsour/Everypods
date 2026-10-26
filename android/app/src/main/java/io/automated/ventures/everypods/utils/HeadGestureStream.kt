package io.automated.ventures.everypods.utils

/** Bounded, generation-scoped motion commands; confirmed samples distinguish live from requested. */
class HeadGestureStream {
    enum class Command { NONE, START, STOP }
    enum class State { IDLE, STARTING, RECEIVING, RECOVERING, UNAVAILABLE, STOPPING }
    companion object { private val RETRY_DELAYS_MS = longArrayOf(2000, 3000, 5000) }
    private var requested = false
    private var stopping = false
    private var generation = -1L
    var commandAlternate = false
        private set
    private var lastRequestAt = 0L
    private var lastSampleAt: Long? = null
    private var stopRetries = 0
    var retries = 0
        private set

    val acceptingSamples: Boolean get() = requested
    fun matchesGeneration(generation: Long) = this.generation == generation

    fun reset() {
        requested = false; stopping = false; generation = -1L
        lastSampleAt = null; lastRequestAt = 0L; retries = 0; stopRetries = 0
    }

    fun onSample(generation: Long, receivedAt: Long) {
        if (requested && generation == this.generation && receivedAt >= lastRequestAt &&
            receivedAt >= (lastSampleAt ?: Long.MIN_VALUE)) {
            lastSampleAt = receivedAt
            retries = 0
        }
    }

    fun onCommandResult(command: Command, success: Boolean) {
        if (command == Command.STOP && stopping && success) reset()
    }

    fun state(now: Long): State = when {
        stopping && stopRetries >= RETRY_DELAYS_MS.size && now - lastRequestAt >= 2000 -> State.UNAVAILABLE
        stopping -> State.STOPPING
        !requested -> State.IDLE
        lastSampleAt?.let { it >= lastRequestAt && now - it < 2000 } == true -> State.RECEIVING
        retries >= RETRY_DELAYS_MS.size && now - lastRequestAt >= 2000 -> State.UNAVAILABLE
        retries > 0 -> State.RECOVERING
        else -> State.STARTING
    }

    fun reconcile(wanted: Boolean, generation: Long, alternate: Boolean, now: Long, commandAllowed: Boolean = true): Command {
        // Losing ownership/transport abandons commands rather than stopping a peer's stream.
        if (!commandAllowed) { reset(); return Command.NONE }
        if (this.generation != -1L && generation != this.generation) reset()
        if (stopping && wanted && alternate == commandAlternate) {
            // A renewed demand for this same service can safely supersede a failed STOP.
            stopping = false
            requested = false
        }
        if (stopping) {
            val delay = RETRY_DELAYS_MS.getOrNull(stopRetries) ?: return Command.NONE
            if (now - lastRequestAt < delay) return Command.NONE
            lastRequestAt = now; stopRetries++
            return Command.STOP
        }
        // Stop the old sensor service first when the packet variant changes.
        if (requested && (!wanted || alternate != commandAlternate)) {
            requested = false; stopping = true; stopRetries = 0; lastRequestAt = now
            return Command.STOP
        }
        if (!wanted) return Command.NONE
        if (!requested) {
            requested = true; this.generation = generation; commandAlternate = alternate
            lastSampleAt = null; lastRequestAt = now; retries = 0
            return Command.START
        }
        val delay = RETRY_DELAYS_MS.getOrNull(retries) ?: return Command.NONE
        if (now - maxOf(lastRequestAt, lastSampleAt ?: lastRequestAt) >= delay) {
            lastRequestAt = now; retries++
            return Command.START
        }
        return Command.NONE
    }
}
