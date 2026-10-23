package io.automated.ventures.everypods.utils.peer

/** Increases only when the observable snapshot payload changes; heartbeats reuse the revision. */
class PeerSnapshotRevision {
    private var lastState: Map<String, Any?>? = null
    private var revision = 0L

    @Synchronized fun forState(state: Map<String, Any?>): Long {
        if (lastState != state) {
            revision++
            lastState = state.toMap()
        }
        return revision
    }
}
