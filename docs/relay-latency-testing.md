# Relay latency tests

The receiver now reduces accumulated delay by overlapping two consecutive decoded
frames into one PCM frame when the adaptive jitter target falls. Both Opus packets
are decoded in order, preserving decoder prediction state. The overlap begins with
the earlier frame and ends with the later frame, with the same fade weights on both
channels. This removes one frame of audio without a hard splice. Short changes in
sound are still possible; physical listening is required.

Reductions retain the existing pacing: at most one frame per 1.5 seconds in Faster
mode or per 5 seconds in Conservative mode, after sustained lower jitter. A reduction
requires two consecutive real packets. It does not replace packet-loss concealment,
change the network protocol, or flush Bluetooth output. Protective buffer growth
cancels a pending reduction. Stop/suspension clears the pending request with the
playback timeline. Adaptive reduction Off retains the previous no-catch-up behavior.

## Quick comparison

Use the updated build on both devices to expose the new presets on the sender and
apply catch-up on the receiver. Open the sending device's peer playback settings,
expand **Experimental audio tuning**, and select **Preset**.

1. Start with **Low latency** as the baseline. Existing saved profiles remain selected
   until you choose a different preset.
2. Stop the relay, select a test preset on the sender, and start the relay again.
   Changes take effect at the next session, including when a preset is selected
   while a session is already running. Android may require capture consent again.
3. Compare the same audio for several minutes. Note audio gaps, estimated delay,
   jitter target, accepted output buffer and underrun-count increases. Test both
   screen-on and screen-off operation under similar Wi-Fi conditions.
4. Return to **Low latency** between individual tests. Try **Test: combined** after
   testing the individual changes. If jitter 40/80 is unstable, compare jitter 60/120.

All test profiles use 10 ms frames, restricted-low-delay Opus at 160 kbps and Faster
adaptive reduction. Values below are requested milliseconds; Android can clamp
capture and playback buffer sizes.

| Preset | Initial / maximum jitter | Playback buffer | Capture buffer | Retransmission deadline |
| --- | ---: | ---: | ---: | ---: |
| Low latency — baseline | 80 / 160 | 40 | 40 | 20 |
| Test: jitter 60/120 ms | 60 / 120 | 40 | 40 | 20 |
| Test: jitter 40/80 ms | 40 / 80 | 40 | 40 | 20 |
| Test: playback 20 ms | 80 / 160 | 20 | 40 | 20 |
| Test: capture 20 ms | 80 / 160 | 40 | 20 | 20 |
| Test: retransmission 0 ms | 80 / 160 | 40 | 40 | 0 |
| Test: combined | 40 / 80 | 20 | 20 | 20 |

Balanced and Unstable network retain their existing values. The preset picker
scrolls and all new names/descriptions are localized in the 12 resource sets.
Named user presets and the existing 12-byte tuning wire format are preserved.

The transport's existing 6 KiB send-queue limit and worker priorities are not changed
by these profiles. The retransmission test changes only the existing SCTP deadline.

## Catch-up verification

`RelayJitterBufferTest` now lives in `src/test`, so the normal debug unit-test task
runs it. Its simulated output accepts at most 40 ms of audio and consumes it at
real-time speed, including backpressure and a pending write. It exercises both
10/20 ms frames, both adaptive modes, disabled reduction, sequence wrap, real
packet loss, and clearing the timeline for resume. It requires the decrease in
actual presentation age to equal the target decrease, rather than merely checking
the target counter. PCM tests check decode order, stereo fade endpoints, output
duration, silence and full-scale levels. Preset tests cover wire round-trips and
isolation of individual settings.

Receiver logs include `caughtUpFrames` in the jitter-buffer statistics. Each count
represents one frame duration removed from the decoded audio. The count resets on
timeline clear for Stop/suspension. This is separate from concealed/lost packets.

The app's delay estimate does not establish source-to-ear latency. Android's mixer,
Bluetooth transport and AirPods can add delay beyond the app's queue accounting.
An acoustic comparison and listening for catch-up artifacts remain device checks.
