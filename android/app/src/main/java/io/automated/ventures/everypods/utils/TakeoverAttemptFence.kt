package io.automated.ventures.everypods.utils

/** Invalidates delayed connect/retry/disconnect work when a newer intent wins. */
class TakeoverAttemptFence {
    private var generation = 0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
}
