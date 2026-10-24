# Heart-rate sharing and resilience

## Current implementation (2026-10-24)

This work is based on remote `main` at `122d3e2` (PR #50). TICKR and standard
BLE remain available; DIRCON is feature-gated by build configuration. The
historical release-candidate notes below describe the earlier candidate and
should not be used as evidence of validation for this revision.

Sensor samples are timestamped when parsed. Samples delayed in the app queue
for five seconds or more, arriving out of order, or belonging to a replaced
session/socket are discarded before display and before any sharing transport.
This does not measure time spent buffered inside the AirPods themselves.

A five-second gap clears the displayed BPM; TICKR receives zero once. After
15 seconds without a sample, or immediately after connection loss, an explicit
session enters recovery for at most 60 seconds. Writes that restart sensing do
not finish recovery: only a fresh sample does. Recovery keeps the original
deadline across successful writes and uses 2/4/8-second capped retry delays.
It only uses an existing same-AirPods local connection and never takes over
from another device. Stop, ownership change, device change, service destruction,
or deadline expiry ends the session.

Monitor-only sessions recover while the app is foregrounded. On
backgrounding, acquisition stops and intent stays in service memory for at
most 60 seconds; returning to the app within that window resumes bounded
recovery to the same AirPods. Navigating away from the Heart rate destination
ends its monitor-only intent; the Home control remains an app-wide monitor
control until Stop or background expiry. Activity recreation preserves intent.
Sharing remains active when the screen is
left or the app is backgrounded. Process death never restores either intent.

Standard BLE sharing closes its old GATT server when Bluetooth turns off and
recreates the service/advertiser on return within a bounded 60-second outage.
Service/advertising setup has a 10-second callback timeout. Three transient
restart attempts are allowed per failure episode; 30 seconds of continuous
advertising resets the failure budget. Receiver disconnects do not restart the
sensor. A reconnecting receiver must subscribe again, and only new samples are
sent. Confirmed send counts persist across server recreation in one sharing
session. A missing notification completion does not permit an overlapping send
to that receiver. Permission failures end sharing cleanly. AirPods disconnect
broadcasts carry the socket generation observed at the event source; delayed
events from an older socket cannot clear the replacement connection or its UI
state.

TICKR has an explicit Reconnect action while active. It zeros the old reading
and waits for the next real sample before issuing a new pair command. Broadcast
acceptance is not confirmation that Wahoo displayed the value. Failed zero
commands retain interrupted-session cleanup. The existing Gym ownership and
manual-stop rules remain in force through method switches and recovery.
The Heart Rate screen labels TICKR as an unconfirmed broadcast and reports its
broadcast-command count separately from BLE's confirmed notification count.

Device validation for this revision is tracked in
`docs/heart-rate-resilience-validation.md`.

## Historical release-candidate notes (2026-10-17)

Implementation continues in the `codex/librepods-eq-airpods5` worktree. The
uncommitted Android localization changes from the main checkout are merged here,
including the generated locale catalog, speech resources, corrected Italian
resource folder, and language validation script. The main checkout is untouched.

## Interface and preferences

Smart Features contains Heart rate directly below Gym mode. The screen offers
one saved method, default TICKR, and one sharing Start/Stop action. Bluetooth
sharing is for another receiver device; same-Pixel discovery has not worked.
Wahoo/TICKR is explicitly experimental and uses undocumented Android broadcasts.
DIRCON is not exposed in the release interface. Diagnostics are expandable.

Monitor only stops when leaving the screen. Sharing remains active in the
background and has a notification with Stop and navigation to Heart rate.
No heart-rate history or raw measurement capture is stored.

Gym automation defaults off. Once enabled, activating Gym mode via any existing
entry point starts the selected method, provided permissions, compatible AirPods,
and a local AACP connection are ready. Permission or connection failures remain
visible on Heart rate. Deactivating Gym stops only Gym-owned sharing. Manual Stop
suppresses automation until Gym is turned off and on again. Manually started
sessions survive Gym being turned off. An active method switch preserves Gym
ownership and starts only one transport.

## Freshness and recovery

A five-second sample gap clears the displayed BPM and sends zero to TICKR.
At 15 seconds without samples, the explicit sharing session enters bounded
recovery. Recovery waits for the same AirPods to have an existing local AACP
connection and ownership; it never requests takeover. Attempts back off from
two to eight seconds and stop after a one-minute recovery window. A real sample
resets that window. Stop, device/ownership change, and service destruction cancel
recovery. Bluetooth service/advertising failures have at most three retries;
permission and unsupported-device errors stop immediately.

Bluetooth sends only fresh samples to subscribers, with one in-flight
notification per receiver. Confirmed notification callbacks increment Sent. The
advertised device name is in the scan response, avoiding the advertising packet
size limit for long phone names. Connections are counted only when a receiver
accesses this service, rather than including unrelated adapter links.

TICKR Stop always sends `SET_SIM_VALUE(cp=HR_CRUX, value=0)` and does not unpair.
A pending-reset flag (no BPM) allows zeroing an interrupted session the next time
EveryPods' service starts. No streaming resumes automatically after process death
or reboot. A killed process cannot send zero at the instant of its death. Wahoo
broadcast acceptance is not a delivery acknowledgment or proof of displayed BPM.

## Manual acceptance checklist

1. Install a compatible signed update without clearing data. Open Smart Features
   and confirm Heart rate is below Gym mode; check the selected app language.
2. With AirPods worn and connected, select TICKR and Start sharing. Open Wahoo on
   the Pixel and verify live BPM agrees with EveryPods.
3. Stop from EveryPods, then from the notification in a second run. Verify Wahoo
   shows zero and TICKR remains saved. Start again and verify fresh readings return.
4. Select Bluetooth and connect Wahoo on Xiaomi using the Pixel Bluetooth name.
   Verify fresh BPM, subscription status, and increasing confirmed notification
   count. Disconnect/reconnect Xiaomi and verify new readings, with no old replay.
5. Switch methods while active. Confirm the prior transport stops, TICKR receives
   zero when leaving that method, and only the chosen transport runs.
6. Enable Gym automation. Turn Gym on/off through settings, Quick Settings,
   notification, and configured stem action. Verify selected-method use. Press
   Stop with Gym on and verify no restart until the next off/on cycle. Verify a
   manually started session survives Gym being turned off.
7. Lock the screen and use Wahoo for a sustained session. Interrupt the AirPods
   connection briefly; verify no stale BPM, recovery only after fresh samples,
   and that Stop during recovery prevents a later restart. Verify another
   device taking ownership ends sharing.
8. Check audio, EQ, head gestures, handover, Gym timers, language switching, and
   denied permission/error presentation.

Builds and local automated checks do not establish these receiving-app results.
The earlier prototypes were manually confirmed for Pixel-to-Xiaomi Bluetooth
and same-Pixel Wahoo/TICKR; this revised candidate requires the checklist above.

## Candidate verification — 2026-10-17

- Version 1.0.4-rc2 (113); debug and signed release assemblies passed.
- All 190 debug unit tests passed, including real Android broadcast-intent checks
  for TICKR zero-on-stop without unpairing, relay ordering/freshness, Gym session
  intent, and the existing protocol/session tests.
- Display/speech resource validation passed for all ten translated locale folders;
  Git whitespace validation passed.
- Release certificate SHA-256 matches the previous Pixel update certificate:
  `c2569500466542a64702b9e962ab7dcd55acf2cbc7b8cb97d1f92d07bdb4aab1`.
- APK SHA-256:
  `c736d9ed196b17c1a4975bb6a65f0d34ebac97b490259588a4a30e9b2d9f317d`.
- This candidate was not installed or exercised in Wahoo by the coding agent.
  The manual checklist remains outstanding for the user.

## AACP compatibility presentation

Heart rate remains visible below Gym mode and uses the existing `aacpAvailable`
check and `MenuNavRow` disabled presentation, including its information icon.
On the current Xiaomi (SDK 36), it is grey and cannot be opened from that row.
Direct navigation displays the existing localized AACP explanation. The sensor
start action also checks platform AACP capability, including Gym-triggered starts.
The Pixel (SDK 37) retains normal access. This does not affect Xiaomi receiving
standard Bluetooth heart rate in Wahoo from another phone.
