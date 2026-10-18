# Wi-Fi audio relay: implementation plan

Branch: `feature/audio-relay` (off `codex/audio-capture-probe`, which is main `f5a78b5` + 3).
Source of truth: [`audio-relay-product-design.md`](audio-relay-product-design.md) and
[`audio-capture-probe.md`](audio-capture-probe.md). Debug builds only until P5 passes;
no Play bundle, no PR from this branch without PM sign-off.

Goal: audio from a source app on the Xiaomi is captured, Opus-encoded, encrypted and sent
over local Wi-Fi to the Pixel, which plays it on the AirPods it manages, while the Pixel keeps
the AirPods AACP link and live heart rate (HR) reaches Wahoo on the Xiaomi.

## Product defaults (PM, Nikos can override)

- Our own announcements/TTS on the Pixel **duck** the relay; only a permanent focus loss stops it.
- Capture **all eligible media apps** by default; per-app filter later (Advanced).
- **Mute the Xiaomi speaker** during relay; restore on stop (only if still muted by us).
- No lip-sync promise. Dev/debug only until P5. Play declarations (FGS `mediaProjection`
  video demo, Data safety, PRIVACY.md) are planned after P5.

## Coexistence approach (P0)

The dev build must install next to the production Play app on both phones.

- **Variant:** `applicationIdSuffix = ".relaydev"` on the existing `debug` build type, so the
  debug APK is `io.automated.ventures.everypods.relaydev`. A separate `relayDebug` type was
  rejected: CI and every developer already run `assembleDebug`/`testDebugUnitTest`; adding a
  fourth type would leave plain `debug` able to overwrite nothing but also untested for the
  flag. `playDebug` is created with `initWith(debug)` and therefore inherits the suffix too
  (it must never replace the Play install either). `release`/`playRelease` are unchanged.
- **Identity:** label "EveryPods Relay (dev)" (debug `res` overlay), orange-tinted launcher
  background plus "DEV" badge.
- **BuildConfig flags:** `RELAY_DEV` (true only on debug-derived types),
  `CROSS_DEVICE_UUID` and `CROSS_DEVICE_SERVICE_NAME` (dev namespace on debug).
- **Relay-only by default:** in a `RELAY_DEV` build, AACP / L2CAP connect, music takeover,
  A2DP `connectAudio`, lid-open autoconnect and the CrossDevice peer channel are **disabled**
  and `AirPodsService` is not started (`RelayDevMode.aacpEnabled == false`). A dev-only
  switch (pref `relaydev_enable_aacp`, default false) re-enables them for deliberate testing.
- **Production detection:** `<queries><package android:name="io.automated.ventures.everypods"/>`
  and a `PackageManager` lookup; the dev app shows a warning banner when production EveryPods
  is installed.
- **Test rule:** before turning the dev AACP switch ON, force-stop or disable production
  EveryPods on **both** phones; turn it back on afterwards. Relay-only testing (the default)
  needs no change to production: production keeps AACP/HR on the Pixel, dev only relays audio.

### Audit (per-install uniqueness)

| Item | Finding | Action |
|---|---|---|
| FileProvider `${applicationId}.provider` | derived from applicationId | OK (becomes `...relaydev.provider`) |
| Hard-coded authorities / account / sync types | none | none |
| Deep link `everypods://` | both apps would offer it (chooser) | debug overlay removes the VIEW filter from the dev build |
| Exported services (2 notification listeners, 2 QS tiles) | component names are package-scoped | OK; listeners only active if the user grants access |
| Widget receivers, BootReceiver | not exported / package-scoped | BootReceiver honours relay-only gate |
| Internal broadcasts (`io.automated.ventures.everypods.*` actions) | receivers use `RECEIVER_NOT_EXPORTED` or `setPackage` for cross-device; actions are hard-coded strings | relay-only mode never starts `AirPodsService`, so no dev receiver is registered; documented risk if AACP is enabled |
| `NotificationAnnouncementService` ignore list | hard-codes production package | adds `context.packageName` so the dev build ignores itself |
| `METADATA_COMPANION_APP` written on the AirPods device | hard-coded production package | intentional: production stays the companion app; dev never writes it in relay-only mode |
| Play Billing | `BillingClient` works without a Play listing; queries fail gracefully | no change; dev build shows no purchasable products |
| SharedPreferences / `AudioLeasePrefs` / LidLease | app-private per package | no collision locally |
| CrossDevice RFCOMM UUID `1abbb9a4-...-8953c5471342` + record `EveryPodsCrossDevice` (server and `CrossDeviceClient`) | shared across installs and devices: dev would peer/lease with production | dev uses its own UUID and record name from BuildConfig |
| Notification channel IDs | per package | fine |

### Risks

1. **Two AACP owners on one phone** (L2CAP PSM 0x1001, takeover, autoconnect, lid). Mitigated by
   relay-only default + explicit switch + banner + test steps above.
2. **Cross-device lease fights** between dev and production. Mitigated by the separate RFCOMM UUID.
3. **Two notification listeners announcing twice** if the user grants the dev listener: dev
   ignores itself; do not grant it while testing relay.
4. **Mac disk** (~39 GB free): build debug only, keep logcat filtered and size-capped.
5. **Playback-capture refusal / projection revocation on lock** (probe evidence): handled as
   distinct states in P2/P4.

## Phases and acceptance criteria

### P0 Setup
- Branch, `.relaydev` debug suffix, label/icon, BuildConfig flags, peer-namespace isolation,
  relay-only gate + dev switch, production-detection banner, `<queries>`.
- `:audio-probe` stays out of Play/release: it is not a dependency of `:app`; CI runs only
  `:app:*` and `:relay-core:*` tasks; `play-release.yml` runs `:app:bundlePlayRelease` only.
- **Accept:** `aapt2 dump badging` shows `io.automated.ventures.everypods.relaydev`, authority
  `...relaydev.provider`; both packages installed side by side, production versionCode
  unchanged; dev app shows the banner; logcat shows `RelayDev` AACP disabled; unit tests +
  `assembleDebug` green locally and on CI.

### P1 Foundations (`:relay-core` + native Opus)
- libopus 1.5.2 via NDK/CMake (vendored source, fetched by a pinned, checksummed script),
  JNI encoder/decoder at 48 kHz stereo, 20 ms, 192 kbit/s constrained VBR, `OPUS_APPLICATION_AUDIO`.
- Versioned packet format, AEAD session (X25519 + HKDF-SHA256 + AES-256-GCM), replay window,
  control-channel framing.
- **Accept:** host unit tests for packet, crypto (tamper, replay, reorder, wrong key), control
  framing; instrumented codec round-trip test compiles (`connectedAndroidTest` on device later);
  `:app` tests unchanged and passing.

### P2 Relay engine (main app)
- Source: `MediaProjection` + `AudioPlaybackCaptureConfiguration` (USAGE_MEDIA/GAME/UNKNOWN, all
  eligible apps), FGS `mediaProjection`, Opus encode, encrypted UDP send, mute/restore speaker.
- Receiver: FGS `mediaPlayback`, adaptive jitter buffer (start 200 ms, 100–500 ms), PLC/FEC,
  refill after long stall, clock-drift correction (resample ±0.1%), AudioTrack with
  `setPreferredDevice` = the managed AirPods (matched by address), verify routed device before
  writing real samples, never speaker fallback.
- Focus: our TTS/announcement → duck relay; transient loss → duck/hold; permanent loss → stop.
- `relayActive` flag blocks music takeover and lid-open autoconnect on both devices.
- Stop: flush, restore volume, no leftover audio. Metrics: underruns, late/lost, latency.
- **Accept:** 10-minute VLC session Xiaomi→Pixel AirPods with < 1 audible glitch/min, takeover
  and lid autoconnect logged as blocked, announcement ducks relay, Stop leaves no audio.

### P3 Pairing, discovery and network
- Reuse the paired-peer list; authenticated key exchange (see decision Q2); NSD `_everypods-relay._udp`
  (dev: `_everypodsdev-relay._udp`); shared Wi-Fi and Pixel-hotspot paths; receiver start without a
  tap where the OS allows (document per device); local-network permission.
- **Accept:** start from Xiaomi with one tap + capture consent on both network paths; address
  change recovers without user entry; unknown device rejected.

### P4 UI
- Per-peer actions "Bluetooth handover" and "Wi-Fi audio relay", remembered last mode, no
  auto-switching; active-relay screen with Audio and HR status separately; distinct errors for
  capture refused, silence and network loss; EN/ES/EL strings, large-font check.
- **Accept:** screenshots per state in 3 locales; `validateLocalizationResources` passes.

### P5 Validation
- Matrix: YouTube, VLC, Pocket Casts × foreground/background/locked × Wi-Fi/hotspot; 30+ min
  session; app killed; projection revoked. Measure latency, loss, drift, battery.
- **Accept:** audio heard on the Pixel's AirPods while fresh HR reaches Wahoo on the Xiaomi for
  the whole session; results table committed. Then Play declarations.

## Decisions

### Q1: Opus and secure-UDP implementation
- **Opus:** upstream **libopus** (BSD-3-Clause) built from source with the NDK through the app's
  existing CMake toolchain, behind a small JNI wrapper. Android's `MediaCodec` Opus encoder is
  not guaranteed on all devices (encoder is optional; decoder is present API 29+), and Java ports
  are unmaintained. BSD-3 is GPLv3-compatible (the repo is GPL-3.0-or-later); keep the libopus
  `COPYING` notice and list it in AboutLibraries.
- **Secure UDP:** no third-party DTLS/QUIC stack. DTLS libraries for Android are heavy (BoringSSL
  via Conscrypt does not expose DTLS; BouncyCastle DTLS adds ~5 MB and a new attack surface), and
  QUIC (Cronet) is not a peer-to-peer server. Instead an app-level AEAD built only from platform
  crypto (minSdk 33): X25519 (`KeyAgreement("XDH")`), HKDF-SHA256 (HMAC), AES-256-GCM, a 96-bit
  nonce = direction byte ‖ 0 ‖ 64-bit sequence, and a 1024-packet sliding replay window. The
  packet header is AAD. Keys are per session; sequence never repeats per key; rekey by new session.

### Q2: Credential exchange channel
**Decision: the existing EveryPods peer channel (paired-peer list over the Bluetooth RFCOMM
CrossDevice link) plus an app-level identity handshake; not Companion Device Manager for pairing.**
- Both phones already have a bonded, user-approved Bluetooth relationship in EveryPods; exchanging
  long-term X25519 identity public keys over it avoids a second device list (design requirement)
  and works without Wi-Fi.
- The CrossDevice link is insecure RFCOMM, so it is treated as untrusted transport: the identity
  exchange is confirmed once by the user (short authentication string shown on both screens),
  then pinned. Session keys are always derived from fresh ephemeral X25519 + both static keys,
  so a passive or later attacker gains nothing.
- CDM is reserved for P3's receiver cold-start question (Q3): a CDM association may be what lets
  the Pixel start its receiver FGS without a tap. It is optional and does not hold credentials.
- Forgetting a peer deletes its pinned key (revokes relay).

## Status log
- P0/P1 started 2026-10-18.
