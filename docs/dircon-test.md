# DIRCON heart-rate test on the latest normal-ID EveryPods

Current source base: `origin/main` commit `437e74fe` (merged PR #49), plus
connection hardening and the case-artwork state changes in this branch.

The current test uses the normal **EveryPods** package
`io.automated.ventures.everypods`, built with `assembleDebug`. Wi-Fi audio relay
is included in normal builds; DIRCON is enabled in debug and remains disabled in
release builds. The old `relayPreview` build type has been removed. This supersedes
the older isolated `io.automated.ventures.everypods.dircon` test described in the
historical checks below. The user explicitly requested the normal app ID.

Source and task-owned changes are in the attached `dircon-latest` worktree.
The original checkout and its unrelated edits remain intact.

## Build

From this worktree's `android/` directory:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Unit tests include actual loopback TCP exchanges; these do not establish Android
Wi-Fi discovery, sensor accuracy, or receiving-app display.

## Live setup

1. Connect the Pixel to Wi-Fi. Use compatible AirPods with HR support and keep
   them worn. Open EveryPods, grant its Bluetooth permissions, and
   establish its AirPods connection. The older isolated test app must stay stopped to avoid competing AACP connections.
2. Open Smart Features → Heart rate. Select **Wi-Fi / DIRCON · Test**, then
   **Start sharing**. Grant notifications and local-network access when asked.
3. Confirm real BPM updates. Expand Diagnostics. The sensor should be named
   **EveryPods HR**, with a Wi-Fi address and port 36866.
4. In Zwift on the same Pixel, complete account/download setup, then open the
   pairing screen and search **Heart Rate**. If a connection-type filter is
   available, include network devices. Grant Zwift its own requested network
   permissions. Do not pair a fake power source or start an activity for this test.
5. If the app does not offer the pairing screen without speed/power equipment,
   record that limitation; use genuine equipment if already available.

## Evidence ladder

- **Advertised**: mDNS registration succeeded. This does not establish Zwift saw it.
- **Receivers > 0**: an app opened TCP. Record whether it was Zwift or the probe.
- **Protocol requests > 0**: a receiver spoke DIRCON discovery/read/subscribe.
- **Subscribed > 0**: it requested heart-rate notifications.
- **Sent increases**: frames were written to TCP, not proof the app displayed them.
- **Zwift displays matching live BPM**: compare several timestamped readings
  against EveryPods. Only this confirms the receiving-app behavior.

## Independent receiver

From a computer on the same Wi-Fi:

```
python3 android/scripts/dircon_probe.py PIXEL_WIFI_IP --seconds 30
```

Use the IP in test-app Diagnostics. The server binds to that Wi-Fi IP, not
localhost. This probe verifies discovery over TCP, characteristic properties,
subscription acknowledgement and actual sensor notifications. It generates no
heart-rate samples and does not test mDNS or same-phone Zwift discovery. Its
connection contributes to the receiver counters; close it before attributing
those counters to Zwift.

On macOS, `dns-sd -B _wahoo-fitness-tnp._tcp local.` can independently check mDNS.
The endpoint is accessible to other devices on this Wi-Fi only while enabled.

## Stop and interruption checks

- Stop in EveryPods or its notification: advertisement and TCP clients close.
- Restart: new subscribers get no cached BPM; wait for a new sensor sample.
- Lock the Pixel or switch to Zwift: sharing should remain active.
- At a five-second sensor gap, TCP receivers disconnect so retained BPM is not
  silently treated as fresh. A receiver may need to reconnect; check its UI.
- Network/address loss: sharing stops with an error and requires manual restart.
- Ownership/device change or process death: no automatic session restart.
- Stop sharing when finished. Do not run the older isolated test app alongside this build.

No synthetic HR, power, cadence, trainer services, workout recording, commits,
or uploads are part of this test setup.

## Setup verification — 2026-10-23

- Isolated APK assembled and installed on the connected Pixel 10 (SDK 37).
- Both `io.automated.ventures.everypods` (stable rc19-relay) and
  `io.automated.ventures.everypods.dircon` (rc19-dircon-test) are installed.
- Test app launched successfully to its independent permissions screen.
- Zwift package `com.zwift.zwiftgame` is installed.
- All 201 debug unit tests passed, including 3 new loopback transport tests.
- `lintDirconDebug` passed with warnings; localization validation passed for
  all 11 locale folders. A lint analyzer crash on a constructor property was
  resolved by moving that property declaration into the inner class body.
- APK SHA-256: `fb01f9aadfb7fa6dd34cccf1f1d044a6c5b20df2107ef8915a7ec4ec5fc6509c`.
- Real AirPods samples in the isolated app, Android mDNS advertisement,
  same-phone Zwift discovery/display, and interruption behavior remain pending.

## Live receiver check — 2026-10-23

- The user granted the isolated app's permissions and wore both AirPods Pro 3.
- Stable and test EveryPods initially competed for the AirPods connection: the
  stable process received sensor traffic while the test session timed out.
  Force-stopping stable did not keep it stopped. The user independently
  uninstalled stable; a proposed temporary-disable command then returned
  `Unknown package` and made no change. Stable is no longer installed.
- After restarting the isolated app, fresh BPM appeared on its screen.
- Wi-Fi sharing started successfully. An independent receiver on the Mac
  discovered the HR service over TCP, verified notification support, subscribed,
  and received five fresh BPM samples in a five-second run, exiting successfully.
  This occurred while Zwift was foreground on the same Pixel.
- macOS DNS-SD resolved `EveryPods HR._wahoo-fitness-tnp._tcp.local.` on port
  36866, including the HR and Device Information service UUIDs.
- Zwift's initial Ride pairing screen offered Power Source or Speed Sensor;
  heart-rate pairing was not accessible in the observed setup. No compatible
  primary exercise sensor was available. The user asked to stop at the verified
  DIRCON stream. No workout was started and no virtual trainer data was added.
- Same-phone Zwift discovery/subscription/display is still unverified.
- Corrected the independent probe to treat its requested observation deadline as
  normal completion rather than a sensor timeout. The successful five-sample
  run used that corrected probe.

## Same-phone receiving-app check — Incyclist, 2026-10-23

The user requested another app and specified no paid account. Installed the free
Google Play build of Incyclist (`com.incyclist.app`, version 1.3.3, code 59) on
this Pixel. No paid membership was used.

- Incyclist discovered **EveryPods HR 1.38** on the same Pixel and showed a BPM
  beside it in its sensor search. Its device screen identified the sensor with
  the Wi-Fi icon.
- EveryPods logged service/characteristic discovery and successful HR subscription.
- Incyclist's `Ble-HR` receiving logs explicitly identify `device: EveryPods HR`,
  `interface: wifi` and fresh decoded HR notification payloads. Repeated samples
  were observed across more than 30 seconds, including after app relaunch.
- This establishes same-phone mDNS discovery, TCP connection, subscription,
  parsing and receiving-app HR display. It is not evidence of workout accuracy,
  saved-workout HR integrity, or prolonged background reliability.
- Incyclist's demo/simulator ride also emitted its own synthetic HR alongside
  the real Wi-Fi readings, contaminating the dashboard/summary. Treat that ride
  as an app demo, not a real workout or a valid HR summary. No trainer metrics
  or synthetic HR were added to EveryPods' endpoint.
- A Wahoo 7.3.0 sensor search did not show this endpoint in the observed attempt;
  EveryPods diagnostics subsequently showed zero protocol requests for that
  session. This is an unsuccessful attempt, not proof of universal incompatibility.

Official app/source references:
- https://play.google.com/store/apps/details?id=com.incyclist.app
- https://incyclist.com/
- https://github.com/incyclist/mobile/tree/main/src/bindings/direct-connect
