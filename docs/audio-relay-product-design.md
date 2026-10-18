# Wi-Fi audio relay: product design and implementation handoff

## Purpose

Let a user play audio from a source app on one Android device (for example, a Xiaomi tablet) through AirPods that remain connected to a Pixel running EveryPods. This keeps the Pixel's AirPods AACP connection available for AirPods heart-rate monitoring while a fitness app on the source device receives the readings.

The feasibility prototype demonstrated this combination in a user-run test:

- VLC on Xiaomi sent audio over Wi-Fi to Pixel and out through AirPods.
- AirPods remained connected to EveryPods on Pixel and produced live heart-rate readings.
- Wahoo on Xiaomi received the heart-rate sensor; Wahoo on Pixel also received the TICKR bridge.

The user reported that the complete setup worked, with audible dropouts in relayed audio. The current prototype is therefore evidence of feasibility, not a shippable implementation. It uses unencrypted TCP and raw mono PCM, has manual address/code entry, and does not yet provide pairing, discovery, reconnection, production-quality audio buffering, or integrated EveryPods UI.

## Decisions already pinned

### Network paths

Support both:

1. **Existing shared Wi-Fi.** Both devices join the same home, work, or travel network. Internet access continues through that network.
2. **Pixel hotspot.** Xiaomi joins the Pixel hotspot. The Pixel can share mobile data where Android, the carrier, and the device allow it. Treat local audio relay and internet availability as separate status signals.

The relay should use a local device-to-device path. It must not silently enable mobile tethering or move either device's general internet traffic. Wi-Fi Direct is not in the first release scope.

Android references: [Pixel hotspot and tethering](https://support.google.com/pixelphone/answer/2812516?hl=en), [local-only hotspot](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot), and [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p).

### Audio encoding and transport

Use **Opus at 48 kHz stereo, 192 kbit/s constrained VBR, with 20 ms frames**, tuned for music. Disable echo cancellation, noise suppression, and automatic gain control. Carry media over encrypted UDP using a maintained transport implementation; use a reliable authenticated channel for setup, control, and status. Add sequence numbers and timestamps, bounded jitter buffering, deadline-aware loss recovery, and packet-loss concealment.

Start the adaptive jitter buffer near **200 ms**, with an approximate **100–500 ms** operating range. Prefer steady playback over minimum latency. Pause and refill after a long stall instead of producing repeated short glitches, and correct sender/receiver clock drift during long sessions. Flush queued audio on Stop and source changes.

The 192 kbit/s choice leaves quality headroom for music while remaining modest on Wi-Fi; it is not lossless. The engineering target for end-to-end delay is roughly **400–800 ms**, subject to measurement and device testing. Do not promise video lip-sync: Android playback capture does not let EveryPods delay the source video's picture to match the relayed sound. The current probe still sends uncompressed 48 kHz mono PCM over TCP; none of the selected production codec/transport behavior is implemented yet.

References: [Opus](https://opus-codec.org/), [Opus recommended settings](https://wiki.xiph.org/Opus_Recommended_Settings), and [RTP payload format for Opus](https://www.rfc-editor.org/rfc/rfc7587.html).

## UX decision under review

### Modes the user sees

Offer two explicit actions for each configured peer, and remember the last selected mode for that peer:

| Mode | What happens | User impact |
|---|---|---|
| **Bluetooth handover** | AirPods connect to the source device through the existing EveryPods handover flow. | Familiar direct playback and low latency. AirPods leave Pixel, so Pixel HR monitoring and Pixel-side HR sharing stop while the AirPods are there. |
| **Wi-Fi audio relay** | AirPods stay connected to Pixel; captured source-app audio is sent to Pixel and played over its existing Bluetooth output. | Pixel retains the AirPods AACP/HR link. Audio uses a local Wi-Fi path and has more delay; video may be out of sync. |

Do not auto-switch between the modes. In Wi-Fi mode, a Bluetooth takeover can silently remove the AirPods HR connection and stop the relay's output. Make a Bluetooth switch a deliberate action with a clear consequence, such as **“Move AirPods to Xiaomi — heart-rate monitoring on Pixel will stop.”**

### Pair once, then start with minimal effort

Reuse EveryPods' existing trusted-peer list and Bluetooth peer setup rather than creating a second device list. Add authenticated relay identity and key exchange to the pairing flow. Pairing should establish a stable device identity and exchange credentials; it must not depend on a remembered IP address, Bluetooth MAC remaining unchanged, or a recurring six-digit code. Forgetting a peer should revoke its relay credentials.

The intended everyday flow starts on the source device:

1. Tap **Play through Pixel** (or the remembered Wi-Fi relay action for that peer).
2. EveryPods finds the paired Pixel and checks that AirPods are connected there.
3. EveryPods finds a usable local network route and prepares the Pixel receiver.
4. Android asks the user to approve playback capture for this session.
5. Audio starts and local source-device playback is muted while relay is active.

Target one app action plus Android's required capture approval. Do not imply pairing can permanently grant Android playback-capture consent: each new MediaProjection session requires fresh user approval, and Android can stop projection. Explain that the prompt captures audio only; it does not record or transmit the screen. Playback capture also depends on source-app policy and Android audio usage.

Start with **all eligible media apps** as the default capture scope to avoid requiring the user to select the playing app. Put a per-app filter in Advanced settings for troubleshooting. Explain that some apps or protected content may refuse capture.

Android references: [Audio playback capture](https://developer.android.com/media/platform/av-capture), [MediaProjection](https://developer.android.com/media/grow/media-projection), and [foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

### Connection setup and recovery

- If both devices have a reachable route on the same Wi-Fi, use it automatically after validating reachability.
- If no route is available, offer **Use Pixel hotspot** with OS-mediated setup and clear internet/data status. Remember the user's network preference, but validate the route each time.
- Keep the local audio-link status distinct from internet status. A working relay does not mean either device has internet.
- Avoid recurring IP entry and numeric codes. Discover paired peers, exchange endpoint details, and authenticate the control/media sessions.
- A short Wi-Fi interruption may reconnect within the current capture session and consent token. If capture itself is revoked or the session ends, show **Tap to resume** and request consent again.
- Never fall back to Bluetooth handover automatically. Offer an explicit **Switch to Bluetooth** action.
- On unrecoverable failure, stop capture, flush playback, restore source volume if EveryPods changed it, and show a specific retry path.

On Android, cold-starting the receiver while its app is backgrounded may need a user tap or an OS-approved companion-device association. Validate this before promising fully unattended startup. Android 17 local-network permission and older platform network permissions also need to be handled in the integrated app.

References: [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission), [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing), and [TetheringManager](https://developer.android.com/reference/android/net/TetheringManager).

### Active relay screen

Keep the state understandable without exposing transport details:

```text
Playing through Pixel
AirPods connected to Pixel

Audio          Streaming
Heart rate     128 BPM · Updated now
Internet       Available

[Stop] [Switch to Bluetooth]
```

Show **Waiting for audio**, **Buffering**, **Reconnecting**, **Action needed**, and **Stopped** states when appropriate. Display heart rate only from a fresh valid sample. Audio streaming and HR availability are separate: one can work while the other is unavailable. Keep codec, packet loss, jitter, and route diagnostics under an expandable diagnostics section.

Stopping Wi-Fi relay stops capture and playback, restores any volume change, and keeps Wi-Fi relay as the remembered mode. It must not immediately initiate Bluetooth handover. HR sharing that the user started separately remains independently controlled. Navigating away should not accidentally stop an active relay; provide persistent notification controls and an explicit stop action. Background operation and notification behavior require real-device validation.

## Alternatives considered and their trade-offs

| Design | Benefits | Costs and user impact | Recommendation |
|---|---|---|---|
| One global Bluetooth/Wi-Fi toggle | Very small UI. | Awkward with multiple peers; mode is unclear at action time; easy to initiate a surprising handover. | Do not use as the primary control. |
| Per-peer remembered mode with two explicit actions | Predictable, supports multiple peers, remembers the user's usual path, and makes HR consequences visible. | Adds a small amount of peer UI and needs clear first-use education. | **Recommended.** |
| Automatically choose Bluetooth or Wi-Fi | Fewest taps when conditions are ideal. | A network, battery, AirPods, or peer-state change could move audio and terminate HR unexpectedly. Harder to explain failures. | Do not auto-select or auto-fallback in the first release. |

## Implementation plan for the receiving agent

### Phase 1: integrate and secure the prototype

- Start from the remote branch identified below. Preserve `android/audio-probe` as a reference/diagnostic module until the integrated path is validated.
- Choose a maintained Android-compatible Opus and encrypted datagram transport implementation. Check licensing, Android API support, maintenance health, and ability to provide reliable setup/control before selecting it.
- Design authenticated peer identity, pairing/key rotation, replay protection, endpoint discovery, session negotiation, keepalive, and teardown. Do not carry forward the prototype's plaintext six-digit header as production security.
- Define a versioned media packet format with sequence number, sender timestamp, stream configuration, and bounded payload sizes.

### Phase 2: build the relay session

- Implement source capture using Android AudioPlaybackCapture and MediaProjection with fresh user consent, foreground-service notification, and explicit capture scope.
- Implement Pixel receiver and Bluetooth AudioTrack playback, verifying the actual routed device before releasing media samples.
- Implement Opus encoder/decoder at the pinned settings, encrypted UDP media, reliable control, jitter buffer, packet-loss handling, clock-drift correction, and clean flush/restart behavior.
- Keep EveryPods AACP/HR and relay audio state independent. Coordinate with existing Bluetooth handover so an active relay cannot be displaced by app-triggered takeover.

### Phase 3: pairing and network setup

- Integrate with existing EveryPods peer settings and trusted peer identity.
- Add reachable-peer discovery and endpoint exchange on existing Wi-Fi and Pixel-hotspot paths; handle changing addresses without user entry.
- Guide the user through a hotspot only when needed and report internet separately.
- Validate receiver cold-start/background behavior and local-network permission across supported Android versions and Pixel/Xiaomi builds.

### Phase 4: user experience

- Add per-peer **Bluetooth handover** and **Wi-Fi audio relay** actions, remembered mode, first-use explanation, concise active session status, persistent controls, and explicit switch/stop/retry actions.
- Start capture only after user action and Android approval. Keep app filters and transport diagnostics secondary.
- Localize all new strings using EveryPods' resource-driven localization approach and verify large-font layouts.
- Make relay loss, HR loss, Bluetooth handover, source volume restore, network change, projection revocation, and recovery states distinguishable.

### Phase 5: validation and release gates

- Test both network choices with Wi-Fi internet, hotspot/mobile data, hotspot without internet, multiple network interfaces, IP changes, and network interruption.
- Test YouTube, VLC, and Pocket Casts where Android/source-app policy allows capture; test foreground/background changes, screen lock, notification Stop, app process death, and projection revocation.
- Measure end-to-end delay, jitter, packet loss, drift, underruns, CPU, battery, and audio quality over extended sessions. Compare with the target, then adjust only with measurement.
- Verify AirPods remain on Pixel; HR remains live on Pixel and reaches Xiaomi Wahoo throughout relay start, stop, retry, and Wi-Fi interruption. Verify explicit Bluetooth handover stops Pixel-side HR as described.
- Verify no stale output after Stop, no unintended speaker fallback, and source volume restoration.
- Run builds and tests for the integrated implementation and record results separately from physical-device checks. The user remains the operator for app interaction and listening checks.

## Handoff branch and current starting point

The work-to-date branch is **`codex/audio-capture-probe`**. It now includes the latest fetched main commit `f5a78b56` (Android settings/search UI updates, PR #47), merged on top of the original base `75c3a078985392d002055edd673447641753e516`. It contains the separate audio-capture/relay feasibility module and this design handoff. It does not contain production Opus, encrypted transport, automatic pairing/discovery, or product UI.

The branch did not exist on `origin` when this handoff was prepared. Publish it as:

`https://github.com/ntsour/Everypods/tree/codex/audio-capture-probe`

Build the probe with:

```sh
./android/gradlew -p android :audio-probe:assembleDebug
```

The prototype procedure and its observed limitations are in [`audio-capture-probe.md`](audio-capture-probe.md). The user's demonstrated concurrent audio/HR result is described above; the observed audio dropouts remain a production quality issue.

## Open design questions for implementation review

1. Which maintained Opus/secure-UDP implementation best fits this Android project's license and min-SDK requirements?
2. Can the existing EveryPods peer channel safely exchange persistent relay credentials, or should pairing use Android Companion Device Manager plus an app-level identity handshake?
3. Which Android/OEM combinations can start the receiver as a foreground service from the paired source device without a Pixel-side tap?
4. Which source apps/content refuse playback capture, and how should the UI identify that case distinctly from silence or network failure?
5. What delay and battery measurements are acceptable for music listening after the first instrumented builds?

These questions should be answered with code/library review and device evidence. They do not change the two already pinned network paths or the selected Opus target.
