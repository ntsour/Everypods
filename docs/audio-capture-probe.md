# Audio capture feasibility probe

Branch: `codex/audio-capture-probe`, based on main `75c3a07`.

A separate Android application (`io.automated.ventures.everypods.audioprobe`),
version 0.2. Main EveryPods remains installed and unchanged. Includes optional
local Wi-Fi audio relay; no root, handover or AirPods protocol changes.

## Design

- Select YouTube, VLC or Pocket Casts. Missing installations are reported.
- Request RECORD_AUDIO, notification permission and per-session MediaProjection
  consent. RECORD_AUDIO is required by Android for digital playback capture;
  the microphone is not an audio source.
- AudioRecord captures only the selected app UID, with MEDIA/GAME/UNKNOWN usages.
- A foreground mediaProjection service allows switching apps, with a Stop action
  in its ongoing notification. Process recreation never restarts capture.
- 48 kHz mono 16-bit PCM; a bounded ring holds only the last ten seconds in memory.
  Start and Clear erase the previous ring; no audio files. Capture-only mode
  never transmits audio. Explicit relay mode sends it to the selected receiver.
- Diagnostics show total sampled seconds, recent RMS dBFS and system/record errors.
  Silence still produces sample frames; duration alone is not success.
- Stop preserves the final ring for a local listening check. Playback requests
  transient media audio focus, plays once and stops if the activity leaves the
  foreground. No microphone recording or persisted recording history.
- Optional media-volume-zero probe restores the original volume on Stop only if
  volume is still zero, respecting subsequent manual changes. Android/process
  termination without cleanup cannot guarantee restoration; restore manually if
  necessary. Volume is a device setting, not an app-private mute.
- English, Spanish and Greek interface; other system locales use English.

## User-run validation on Xiaomi

Keep AirPods connected to Pixel. Do not use Xiaomi Bluetooth to connect AirPods.
Main EveryPods can continue HR sharing to Xiaomi Wahoo during these checks.

For each app:

1. Open **EveryPods Audio Probe**, choose the app and Start. Approve microphone
   permission (for internal capture) and the Android projection prompt. Select
   **entire screen** if offered. The probe does not create a video recording.
2. Open the selected app and play content for at least 15 seconds. Return to the
   probe, Stop and Play last 10 seconds. Listen on Xiaomi. An audible matching
   sample establishes capture for that specific content and app configuration.
3. Start again, enable mute in the probe, switch to the source app and play another
   15 seconds. Return, Stop (restoring volume) and Play. Confirm both that Xiaomi
   was silent during capture and that the captured sample is audible afterward.
4. Repeat while Wahoo is foreground, with the source app playing in the background
   when supported by that app/account. Confirm capture and Pixel HR remain live.
5. Separately lock Xiaomi for 15 seconds while playback continues, then unlock.
   Check the probe for system-ended capture and replay the remaining sample.
   Lock support is unproven; Android may revoke projection on lock. Source apps
   may independently stop background playback.
6. Stop and Clear after testing. Never count a rising duration or a remembered
   level as proof that the desired audio was captured.

Record for each app: content audible in sample, capture while muted, Wahoo
foreground, lock behavior, and any diagnostic text. YouTube protected content
and playback policies may differ from ordinary videos; VLC passthrough and
other output settings may affect capture. This is a feasibility probe, not a
promise of compatibility for all content.

Build: `./android/gradlew -p android :audio-probe:assembleDebug`
APK: `android/audio-probe/build/outputs/apk/debug/audio-probe-debug.apk`
The user confirmed version 0.1 captures audible samples from YouTube, VLC and
Pocket Casts on Xiaomi both normally and with media output muted. Background,
lock behavior and end-to-end relay remain unverified.


## Version 0.2 — Wi-Fi relay

### User procedure

1. Keep AirPods audio connected to Pixel. In main EveryPods on Pixel, Start
   Bluetooth HR sharing and confirm Wahoo on Xiaomi receives live measurements.
2. Force-stop **main EveryPods on Xiaomi** using Android app settings. Leave Wahoo
   running. This prevents its automatic music takeover from invalidating the test.
3. Either keep both devices on the same LAN (simplest first test), or manually
   enable Pixel hotspot and join from Xiaomi. Changing networks can disconnect ADB.
4. In Audio Probe on Pixel, tap **Receive on Pixel** and allow local network access.
   It requires an already connected Bluetooth audio device. Note displayed IPv4
   addresses and session code. Choose the address on Xiaomi's current network.
5. In Audio Probe on Xiaomi, select the source app, enter that Pixel address and
   code, then **Capture and send to Pixel**. On Pixel hotspot only, **Use Wi-Fi
   gateway** can fill the destination. Approve Android's capture prompt.
6. Enable mute on Xiaomi and play content in the selected app. Check receiver
   says connected and shows the AirPods output, byte counters rise, and the actual
   source audio is heard through AirPods. Confirm Pixel still receives new HR
   samples and Xiaomi Wahoo displays fresh matching measurements.
7. Repeat source-app checks. Observe glitches/delay over at least two minutes.
   Separately check app backgrounding and Xiaomi lock/unlock. These conditions
   were not established by the first capture-only confirmation.
8. Stop capture on Xiaomi and receiver on Pixel. Check media volume restores,
   relay audio stops and Pixel HR remains live. Stop HR separately when finished.

### Transport and lifecycle

- Sender sockets bind to the matching Wi-Fi subnet, including hotspots without
  Internet, instead of relying on Android choosing Wi-Fi as the default network.
- Local IPv4 TCP, port 47473, TCP_NODELAY, 48 kHz mono 16-bit PCM (~768 kbit/s).
  This is a feasibility transport, not the final music-quality or video-sync design.
- One sender connection per Start. Six-digit random receiver code changes on
  receiver restart; header authentication precedes playback. The prototype is
  unencrypted and intended for trusted LAN/hotspot testing only.
- Sender accepts private LAN IPv4 addresses only; receiver rejects non-private
  source addresses. No cloud server, discovery broadcast, port forwarding or
  root. The receiver displays interface addresses; no hard-coded hotspot address.
- Frames have declared lengths capped at 9600 even bytes. Capture reads ~20 ms
  chunks; sender queue is bounded to five chunks and drops oldest if congested.
  Socket buffers are small, but overall latency is not measured or guaranteed.
- Connection/authentication timeout 3–5 seconds, stalled sender write watchdog
  2 seconds, receiver read timeout 3 seconds. Failures stop the sender's capture,
  restore volume and require a fresh Start/consent. No automatic reconnection.
- Receiver remains listening after a sender leaves. Stop receiver closes listener
  and client; source Stop closes TCP immediately. Reconnecting requires a new
  sender Start. Process/service recreation never restores a session.
- Receiver uses mediaPlayback foreground service, audio focus and partial wake
  lock. Sender retains mediaProjection foreground service and a partial wake lock
  during relay. Notification actions stop each role; locks release on teardown.
- Receiver selects an existing Bluetooth output. It primes routing with silence
  and checks the actual route before sending real samples. Route loss, removal or
  audio-focus loss closes the stream and flushes playback, preventing deliberate
  speaker fallback. Hardware-level seamless cutoff still needs device validation.
- Android 17/target 37 requests ACCESS_LOCAL_NETWORK; older supported Android
  versions request NEARBY_WIFI_DEVICES for devices enforcing local restrictions.
- UI retains the capture-only controls and adds receiver address/code, sender
  destination/code, connection state and sent/received/drop counts in EN/ES/EL.
- The relay never touches AACP, ownership, audio-profile connect/disconnect or
  EveryPods main service. AirPods stay paired and audio-connected to Pixel.

Build success/install establish packaging only. Success requires simultaneous
real Xiaomi audio heard through Pixel-connected AirPods and fresh Pixel HR
measurements reaching Xiaomi Wahoo. End-to-end validation is user-driven.
