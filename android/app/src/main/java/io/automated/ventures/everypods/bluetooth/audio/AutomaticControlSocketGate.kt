package io.automated.ventures.everypods.bluetooth.audio

/** The control socket may displace another host, so unknown audio state cannot authorize it. */
object AutomaticControlSocketGate {
    fun allows(queryLocalAudio: () -> Boolean?): Boolean =
        try { queryLocalAudio() == true } catch (_: Exception) { false }
}
