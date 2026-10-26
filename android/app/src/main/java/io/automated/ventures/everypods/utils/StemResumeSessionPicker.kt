package io.automated.ventures.everypods.utils

/**
 * Pure selection logic for resuming paused media from an AirPods stem press.
 *
 * Android can lose its "media button session" (dumpsys: "Media button session is null"),
 * e.g. after a call or announcement. A generic KEYCODE_MEDIA_PLAY_PAUSE then reaches nobody
 * even though a paused session (Pocket Casts, ...) is still active. In that case we target
 * the paused session directly via its TransportControls.
 */
object StemResumeSessionPicker {

    // android.media.session.PlaybackState constants (kept as literals so this stays JVM-pure).
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6

    // android.media.AudioAttributes usages for calls.
    private const val USAGE_VOICE_COMMUNICATION = 2
    private const val USAGE_VOICE_COMMUNICATION_SIGNALLING = 3

    private val CALL_PACKAGES = setOf(
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.viber.voip",
    )

    data class Candidate(
        val packageName: String,
        val state: Int,
        /** PlaybackState.lastPositionUpdateTime (elapsedRealtime ms); higher = more recent. */
        val lastUpdateTime: Long,
        val usage: Int? = null,
        val isRemote: Boolean = false,
        val hasPlayAction: Boolean = true,
    )

    enum class Path { DIRECT_SESSION, KEY_EVENT }

    fun isCall(c: Candidate): Boolean =
        c.packageName in CALL_PACKAGES ||
            c.usage == USAGE_VOICE_COMMUNICATION ||
            c.usage == USAGE_VOICE_COMMUNICATION_SIGNALLING

    /**
     * Best paused/stopped session to resume, or null.
     * Order: [preferredPackage] (last played through the AirPods) first, then PAUSED before
     * STOPPED, then most recently updated, then original (Android priority) order.
     */
    fun pick(
        candidates: List<Candidate>,
        ownPackages: Set<String>,
        preferredPackage: String?,
    ): Candidate? = candidates
        .withIndex()
        .filter { (_, c) ->
            (c.state == STATE_PAUSED || c.state == STATE_STOPPED) &&
                c.packageName !in ownPackages &&
                !c.isRemote && c.hasPlayAction && !isCall(c)
        }
        .sortedWith(
            compareByDescending<IndexedValue<Candidate>> { it.value.packageName == preferredPackage }
                .thenBy { if (it.value.state == STATE_PAUSED) 0 else 1 }
                .thenByDescending { it.value.lastUpdateTime }
                .thenBy { it.index }
        )
        .firstOrNull()?.value

    /** Direct play only for a resume press: nothing audible, nothing playing, access + target present. */
    fun decide(
        musicActive: Boolean,
        anySessionPlaying: Boolean,
        hasSessionAccess: Boolean,
        target: Candidate?,
    ): Path = if (!musicActive && !anySessionPlaying && hasSessionAccess && target != null) {
        Path.DIRECT_SESSION
    } else {
        Path.KEY_EVENT
    }

    fun isResumed(state: Int?): Boolean = state == STATE_PLAYING || state == STATE_BUFFERING
}
