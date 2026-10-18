# Experimental heart rate

The **Heart rate · Experimental** screen is under **Audio & Connection** when
the connected model exposes the HRM capability (currently AirPods Pro 3).
Wear the earbuds, establish the local AACP connection, and press **Start**.
The app does not take over audio or automatically reconnect to start HRM.

Readings remain in memory. Stop, leaving the screen, backgrounding the app,
connection loss, and device changes clear the displayed BPM. Reconnecting does
not restart monitoring. After five seconds without a valid sample the value
becomes a dash; after fifteen seconds the session stops and offers Retry.
There is no database, Health Connect export, alerting, or background session.

## Protocol source

Ported from LibrePods commit
[`5dd84464531558b5fcdedca63ae8f6382a766ab1`](https://github.com/librepods-org/librepods/tree/5dd84464531558b5fcdedca63ae8f6382a766ab1),
including its RTBuddy envelope, protobuf schema, and unsigned BPM/status parsing.
The existing AACP opcode `0x17` carries both head-tracking and HRM traffic.
HRM is routed before the legacy movement-frame length check.

Start sends a SensorDataWX service setting with a 1,000,000-microsecond interval:
service 84 for firmware major 9 or later, service 19 for older/unknown firmware.
Stop uses that session's selected service and interval zero. Incoming services
19 and 20 are accepted only with an 18-byte payload, a zero final status byte,
and a nonzero unsigned BPM byte. Other sensor traffic retains the legacy path.
Buddy envelopes are bounded to 4 KiB and reset on disconnect.

## Local device validation

1. Confirm the device-reported model and firmware, rather than the Bluetooth name.
2. Build and install a signed update that matches the installed app certificate;
   preserve its data. Start only while the HRM screen is visible.
3. For a local validation capture, temporarily enable the measurement trace:

   ```sh
   adb -s DEVICE_SERIAL shell setprop log.tag.EveryPodsHRM VERBOSE
   adb -s DEVICE_SERIAL logcat -v epoch -s EveryPodsHRM
   ```

4. Compare multiple incoming `sample bpm=` values with the live screen. A
   successful command write, a waiting state, or a simulated packet is not proof
   of real sensor readings. Keep captures local and outside Git.
5. Verify Stop, navigation away, backgrounding, and disconnection clear BPM and
   end the session. Verify returning/reconnecting does not restart it, and check
   ordinary audio, head gestures, EQ, and handover.
6. Disable the temporary measurement trace afterwards:

   ```sh
   adb -s DEVICE_SERIAL shell setprop log.tag.EveryPodsHRM INFO
   ```

Normal logs contain service choices, counts, session transitions and rejection
reasons, without measured BPM values. General packet logging and packet
broadcasts exclude Buddy traffic and are suppressed during HRM sessions.
If no valid readings arrive, retain the selected service, counters, rejection
reasons, and firmware; do not enable unrelated sensor services automatically.

## Automated checks

```sh
cd android
./gradlew :app:testDebugUnitTest --tests '*HeartRate*Test'
./gradlew :app:assembleDebug :app:assembleRelease
```

Tests cover independent wire fixtures, firmware selection, unsigned BPM above
127, invalid measurements, fragmented/consecutive Buddy frames, unchanged
movement callback dispatch, freshness, timeout, command failures, and session
cleanup without automatic restart.

The release R8 rules preserve generated RTBuddy protobuf fields because the lite
runtime discovers them through reflection. Validate Start in the optimized
release APK as well: JVM tests do not exercise field removal by R8.

## Verified connection setup (17 October 2026)

Real HRM samples were verified in the optimized signed release on Pixel 10,
AirPods Pro 3 model A3063, firmware version3 `9442752`: command service 84,
incoming service 20. The displayed BPM matched timestamped incoming samples.
The connection handshake must match the working LibrePods `e4218c2` baseline:
`00 00 04 00 01 00 03 00 00 00 00 00 00 00 00 00`.
Our earlier `02` at offset 6 produced no valid measurements; replacing it with
`03` restored readings without changing initialization timing or HRM Start bytes.
The exact meaning of that field has not been established.

Diagnostics classify acknowledgments separately from readings and report Buddy,
accepted-sample, acknowledgment and rejection counters. Acknowledgments never
produce a live value. Initial invalid-status measurements can occur before the
sensor produces valid readings; these remain rejected.

Verified on-device: repeated real values, Stop, navigation away and return,
background and return, stream termination and no automatic restart. Stale,
timeout, disconnect and device-change state transitions also have unit coverage.
Other headset models and physical handover require separate device validation.
