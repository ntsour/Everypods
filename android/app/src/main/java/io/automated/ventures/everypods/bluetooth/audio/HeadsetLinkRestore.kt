package io.automated.ventures.everypods.bluetooth.audio

/**
 * The AirPods opened a link to this phone on their own (they reconnect to their last host),
 * but no audio profile followed. Waiting for local media is not enough: with nothing playing
 * the AirPods sit on the bare link and drop it after ~30 s, so auto-connect silently fails.
 *
 * Restore audio when this phone is the last holder and no peer has, or is using, the AirPods.
 */
object HeadsetLinkRestore {
    fun shouldRestore(
        autoConnectEnabled: Boolean,
        lastHolderHere: Boolean,
        peerHolding: Boolean,
        peerUsingAudio: Boolean,
    ): Boolean = autoConnectEnabled && lastHolderHere && !peerHolding && !peerUsingAudio
}
