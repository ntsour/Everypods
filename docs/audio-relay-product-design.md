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

**Network selection implementation:** the sender requests its selected mode over authenticated Bluetooth signaling. For shared Wi-Fi, the receiver selects its registered Wi-Fi network (preferring the active network when more than one exists). For hotspot, it selects a unique downstream private IPv4 interface omitted from Android's upstream Network list. It offers that subnet, the sender confirms a matching Wi-Fi interface, and only then does WebRTC start. A custom WebRTC network monitor exposes exactly that interface; registered Wi-Fi sockets bind to the Android Network handle, while the downstream AP uses its local interface address. SDP/trickled candidates exclude other addresses and TCP. No process-wide binding, Internet rerouting, STUN/TURN, fixed IP addresses, or numbered adapter names are used. Missing or ambiguous interfaces fail explicitly. IPv6-only networks are not supported by this preview's path negotiation. Both screens reflect the sender-selected mode. Changing mode or losing the negotiated network stops the session and requires Start again.

Android references: [Pixel hotspot and tethering](https://support.google.com/pixelphone/answer/2812516?hl=en), [local-only hotspot](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot), and [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p).

### Audio encoding and transport

**Route-selection evidence:** the failed quality experiment selected Pixel's router-side host address and Xiaomi's hotspot-side host address, although both devices also exposed same-subnet candidates. Native enumeration solved AP discovery but permitted mixed-interface ICE paths. The new authenticated interface negotiation replaces that unrestricted enumeration. Build/install success must be distinguished from a fresh user listening check on both network modes.

**Hotspot quality follow-up:** steady source capture but bursty receiver arrivals, missing-frame concealment, and buffer refills were observed. An experiment increasing retransmission lifetime from 80 to 300 ms and adding audio thread priority plus CPU/Wi-Fi locks made listening worse; those tuning changes were reverted. The transport retains its original 80 ms media lifetime. Arrival-gap and AudioTrack-underrun diagnostics remain. The Pixel UI reports a 5 GHz hotspot; multiple interfaces were listed on Xiaomi, so the earlier 2.4 GHz attribution was not established. The underlying cause of the transport gaps remains unresolved.

Use **Opus at 48 kHz stereo, 192 kbit/s constrained VBR, with 20 ms frames**, tuned for music. Disable echo cancellation, noise suppression, and automatic gain control. Carry media over encrypted UDP using a maintained transport implementation; use a reliable authenticated channel for setup, control, and status. Add sequence numbers and timestamps, bounded jitter buffering, deadline-aware loss recovery, and packet-loss concealment.

Start the adaptive jitter buffer near **200 ms**, with an approximate **100–500 ms** operating range. Prefer steady playback over minimum latency. Pause and refill after a long stall instead of producing repeated short glitches, and correct sender/receiver clock drift during long sessions. Flush queued audio on Stop and source changes.

The 192 kbit/s choice leaves quality headroom for music while remaining modest on Wi-Fi; it is not lossless. The engineering target for end-to-end delay is roughly **400–800 ms**, subject to measurement and device testing. Do not promise video lip-sync: Android playback capture does not let EveryPods delay the source video's picture to match the relayed sound. The separate probe still sends uncompressed 48 kHz mono PCM over TCP.

**Implementation status (18 October 2026):** the separate Relay preview now connects the design's main path end to end in code. It includes pinned libopus 1.5.2 at 48 kHz stereo, 20 ms, 192 kbit/s constrained VBR; versioned media packets and bounded jitter buffering; local-only WebRTC ICE with separate reliable control and deadline-limited media channels over DTLS/SCTP/UDP; Bluetooth signaling with persistent P-256 identities, two-sided comparison-code confirmation, ECDH-derived AES-GCM signaling keys, replay checks, and credential revocation when forgetting a peer. The source screen requests Android 17 `ACCESS_LOCAL_NETWORK` when needed, displays an audio-only explanation before fresh MediaProjection consent, captures all eligible app audio, mutes and conditionally restores source volume, and transmits Opus frames. The receiver requires the saved AirPods Bluetooth address to match the selected audio route before releasing captured media, and stops on route loss rather than falling back to the speaker. Service-owned status survives navigation; Stop flushes local capture/playback and closes the peer session. Fresh heart-rate samples and actual sharing transport state are summarized independently of audio. Automatic media/call handover is suppressed on the sending device during relay setup and playback, without rewriting saved handover settings.

Both `:app:assembleDebug` and `:app:assembleRelayPreview` completed successfully. The Relay preview APK was installed with `adb install -r` on the Pixel 10 and Xiaomi Pad (existing app data preserved). This makes the flow ready for hands-on testing; it does not prove that pairing, route selection, capture, or audible playback succeeds on these devices. The user must still verify the two-device setup, Android prompts, real playback from YouTube/VLC/Pocket Casts, uninterrupted HR, Stop behavior, Wi-Fi/hotspot recovery, and explicit Bluetooth handover. Treat any successful build/install as package validation only, not live audio validation.

References: [Opus](https://opus-codec.org/), [Opus recommended settings](https://wiki.xiph.org/Opus_Recommended_Settings), and [RTP payload format for Opus](https://www.rfc-editor.org/rfc/rfc7587.html).

## Current UX decision

The accepted 19 October connection-list design in [the UI specification](design/audio-relay-ui.md) supersedes the earlier per-peer selected-mode proposal below. Save BT handover locally and Wi-Fi relay symmetrically as independent connection types; show saved Wi-Fi peers directly on Home. Suppress automatic handover only while a sender has requested an active relay session. Removing Wi-Fi revokes only relay authorization; identities remain available for authenticated revocation and independent BT coordination.

## Earlier UX analysis (superseded where noted)

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

Capture **all eligible media apps** without an app picker or per-app filter. On Android 14 and newer, request default-display projection consent so authorization does not require selecting an app. Only audio is captured and transmitted. Switching between media apps within the same session requires no new selection. Some apps or protected content may refuse capture.

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

The work-to-date branch is **`codex/audio-capture-probe`**. It now includes the latest fetched main commit `f5a78b56` (Android settings/search UI updates, PR #47), merged on top of the original base `75c3a078985392d002055edd673447641753e516`. It contains the separate audio-capture/relay feasibility module, a per-peer playback-mode settings screen, and this design handoff. The settings UI ships only in the `relayPreview` build type under application ID `io.automated.ventures.everypods.relay`, so it can be installed beside EveryPods. The codec/transport building blocks described above are present in that build type, while authenticated discovery/pairing, the production audio session, and start/stop controls remain unimplemented.

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


## Receiver audio interruptions (20 October 2026)

- A notification/navigation prompt that permits ducking temporarily lowers relay volume; normal volume returns on audio-focus gain.
- A temporary focus loss (for example a call or assistant interaction) suspends receiver playback and resumes only when Android returns focus, provided the session and AirPods route remain valid.
- A permanent focus loss (for example deliberate local media playback) suspends relay until the user selects **Resume relay** on either participant. An unsolicited focus gain must not resume this state.
- Suspension is a service-owned state shown on both Homes, the session sheet/settings, session strip, notification and Quick Settings tile. Resume/Stop are nonblocking actions; an unsuccessful Resume stays suspended with an explanation. The tile retains its participant-only Stop action.
- Retain the current Android capture consent/session while suspended. Do not send generic Play/Pause commands to source apps: their timeline may continue unheard, and the interface explains this. Sender handover suppression remains active until Stop/failure/expiry.
- Pause and flush receiver output, discard incoming suspended/old audio, and reset the decoder/jitter timeline on resume. Resume verifies the original AirPods output before playing new media. Codec, bitrate and buffer settings remain unchanged.
- A continuous suspension expires after two minutes (repeated focus callbacks or failed Resume attempts do not extend it). End capture and playback on both endpoints, restore source volume using the existing user-change-aware policy, and explain that a fresh Start is required. Explicit Stop also fully ends the session.
- AirPods ownership changes, peer removal, session replacement and transport failure retain their existing termination semantics while suspended.

Physical acceptance: relay normally, start local receiver media (both endpoints show paused), stop local media (relay stays paused), explicitly Resume without a new capture prompt, verify a transient interruption resumes on focus gain, verify Stop prevents later restart, and verify two-minute expiry releases capture and handover suppression. The user performs listening and interaction checks.
