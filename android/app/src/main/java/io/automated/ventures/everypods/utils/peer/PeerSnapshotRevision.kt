package io.automated.ventures.everypods.utils.peer

/** Ownership revisions exclude reservation bookkeeping, which has its own request/grant IDs.
 * Otherwise creating a reservation invalidates the ownership revisions it just reserved.
 */
class PeerSnapshotRevision {
    private var lastState: Map<String, Any?>? = null
    private var revision = 0L

    @Synchronized fun forState(state: Map<String, Any?>): Long {
        val ownershipState = state - "acquisition"
        if (lastState != ownershipState) {
            revision++
            lastState = ownershipState.toMap()
        }
        return revision
    }
}
