# Heart-rate resilience: implementation and validation handoff

## Task for the implementing agent

Implement this plan in `/Users/nikos/Everypods`. Improve heart-rate monitoring and sharing continuity through sensor dropouts, connection loss, Bluetooth off/on, receiver reconnects, and Activity lifecycle changes. Add meaningful automated coverage and execute the validation below. Do not stop after writing code or after the narrow unit tests pass. Clearly separate verified behavior from device checks you could not perform.

This document was prepared on 2026-10-23 against commit `5c24f6d`, then updated for remote `main` at `122d3e2` (PR #50). Reinspect the current checkout before editing; line numbers and branch state may change.

The preceding audit changed no code. Its targeted debug test run passed 33 tests under `bluetooth.hrm.*`, including DIRCON protocol tests. Those tests do not establish service recovery correctness.

## Scope and constraints

- Android implementation shared by normal EveryPods and Relay builds. Do not introduce an iOS implementation.
- Preserve existing HR commands, firmware service selection, working AACP handshake, packet validation, and unsigned BPM handling unless a new failing test demonstrates a specific defect.
- Support standard BLE Heart Rate Service and experimental Wahoo/TICKR broadcasts. PR #50 also added a feature-gated DIRCON method and a typed state reducer: preserve and integrate with them. Do not remove or regress DIRCON, and do not enable it in build variants where it is currently disabled.
- Never manufacture, interpolate, repeat, or replay old BPM to hide a dropout. Continuity means preserving session intent and reconnecting safely.
- Recover only to the same AirPods and an existing locally owned connection. Never request takeover as part of HR recovery. Explicit ownership transfer to another device and changing AirPods are terminal for the session.
- Explicit Stop cancels every pending resume/retry. Service destruction, process death, or reboot must not automatically resume monitoring or sharing. Retain interrupted-TICKR zero cleanup.
- Preserve Gym ownership, manual-stop suppression, saved method, permission gating, notification Stop, and Home heart-control behavior.
- No root/Xposed, cloud services, HR history, raw BPM logging, new permissions, or broad Bluetooth/audio refactor.
- Preserve unrelated tracked/untracked work. The audit found unrelated `camera-kit-comparator/` and `tmp/relay-wt/`; do not modify them or use the latter as the target checkout.
- Implementation does not authorize publishing, pushing, opening a PR, changing version/signing configuration, or installing over a user's app without existing authorization. If physical devices or installation authorization are unavailable, finish automated work and provide exact remaining device steps.

## Files to inspect first

All paths below are relative to `/Users/nikos/Everypods`.

| Area | Files |
| --- | --- |
| Session, intent, sharing state | `android/app/src/main/java/io/automated/ventures/everypods/bluetooth/hrm/HeartRateSession.kt`, `HeartRateSharingPrefs.kt`, `HeartRateSharingState.kt` |
| BLE and TICKR adapters | Same directory: `BluetoothHeartRatePeripheral.kt`, `WahooDiagnosticBridge.kt`, `WahooHeartRateRelay.kt` |
| Sensor ingress | `android/app/src/main/java/io/automated/ventures/everypods/bluetooth/AACPManager.kt`, especially parsing and `disconnected()` |
| Orchestration | `android/app/src/main/java/io/automated/ventures/everypods/services/AirPodsService.kt`: HR start/stop/recovery, callbacks, adapter receiver, ownership, Gym, destruction and socket read-loop cleanup |
| Presentation/lifecycle | `presentation/screens/HeartRateScreen.kt`, `presentation/components/HeartRateCaseControl.kt`, `presentation/viewmodel/AirPodsViewModel.kt`, under the same main package |
| Tests/docs | `android/app/src/test/java/io/automated/ventures/everypods/bluetooth/hrm/`; `docs/heart-rate-sharing-release.md` |

Inspect applicable AGENTS.md instructions, git status, and local testing instructions before changing files. Do not copy obsolete worktree/branch claims from the release document.

## Required behavior and timing policy

These are concrete defaults for implementation. Keep constants named and centrally defined. Do not silently expand retry duration or change ownership policy to make tests pass.

| Event | Required behavior |
| --- | --- |
| Start | Record memory-only intent and target AirPods identity. Show waiting; never show a cached BPM. Repeated Start for the same active mode is idempotent. |
| Valid sample | Accept only current-session/current-connection data, with BPM 1–255 and ingestion age strictly below 5 seconds. Forward at most once per accepted sample. |
| Gap reaches 5 seconds | Clear current BPM, mark sensor stale, zero TICKR once for the gap. Keep healthy sharing transport available. |
| Gap reaches 15 seconds, including no initial sample | Enter sensor recovery. Start a 60-second outage deadline at recovery entry. |
| Connection lost | Clear BPM and zero TICKR immediately; enter recovery immediately, with the same 60-second outage budget. |
| Retry | Use 2, 4, then 8-second capped backoff. A confirmed same-device connection-ready event may trigger an immediate attempt without creating a second concurrent attempt. |
| Sensor restart command succeeds | Enter awaiting-fresh-sample. This is not recovery success. Continue enforcing the original outage deadline. Retry after 15 seconds without data, or expire sooner if the outage deadline arrives. |
| First fresh sensor sample | Complete sensor recovery and clear its outage deadline. Do not declare the sharing transport ready on this evidence alone. |
| Recovery expires | Stop the affected session using a clear timeout/failure reason; release resources and cancel timers. Check the absolute deadline after every wakeup and before every retry. Do not stretch it by another backoff or first-sample timeout. |
| Bluetooth turning off/off | Invalidate and close the BLE server/advertiser and clear receiver state. Suspend transport retries until the adapter is on. Keep explicit intent only within its recovery budget. Adapter-off is not unsupported hardware. |
| Bluetooth on | Recreate BLE service/advertising for an authorized unexpired session. Sensor recovery still requires the same AirPods and local connection readiness. |
| Receiver disconnect | Clear that receiver's subscription and pending send state. Keep advertising/server available; do not restart the sensor. On reconnect, require resubscription and send only subsequent fresh samples. |
| Explicit Stop | Invalidate session generation first, cancel all retries/resumes, clear values, zero TICKR, close BLE, cancel notification, release wake lock. Late callbacks cannot restart or repopulate state. |
| Ownership/device change | Stop immediately. A missing link is recoverable; a confirmed transfer to another owner/device is not. Preserve existing no-takeover behavior. |
| Process/service restart | Start stopped, with no persisted session intent/BPM. Attempt existing pending TICKR zero cleanup only. |

BLE transient failures: allow three restart attempts per continuous failure episode, within a 60-second transport outage deadline. Use 2/4/8-second delays. After successful service registration and advertising, mark the transport ready; reset its consecutive-failure budget after 30 seconds continuously ready. A receiver is not required for readiness. If failures recur before that stability threshold, retain the budget so a flapping server cannot retry forever. Permissions and genuinely unsupported hardware are terminal.

If both sensor and transport are recovering, keep their deadlines and readiness independent. The UI's overall recovering indicator may be their logical OR; it must not become the source of truth. Terminal transport failure should retain the current whole-session teardown behavior for this change, with its actual error visible. Preserve the DIRCON controller's own network recovery policy and typed transport state; sensor recovery must compose with it rather than overwrite it.

### Monitoring-only lifecycle policy

This is an intentional behavior change from today's screen-bound preview; implement and document it explicitly:

- While the HR screen is visible, monitoring-only gets the same bounded sensor reconnection behavior as sharing.
- Configuration changes preserve monitoring intent and must not issue duplicate Start commands or be interpreted as explicit navigation away.
- On genuine backgrounding, stop sensor acquisition, clear BPM, and suspend monitoring intent for up to 60 seconds in service memory. Do not keep a monitor-only stream running in the background.
- Resume the same visible HR screen within that grace period only if the target AirPods are unchanged, no explicit Stop/ownership loss occurred, and the previous outage deadline has not expired. Require fresh data before showing BPM. The grace period must not reset an already-running outage deadline.
- Explicit navigation away from the HR destination ends monitor-only intent. Returning later requires Start. Use actual destination/lifecycle ownership, not indiscriminate Compose `onDispose`, to distinguish navigation from recreation.
- Sharing survives navigation, Activity recreation, and backgrounding. Preview cleanup cannot cancel sharing or its recovery.
- Process death clears the grace period. Any wakeup validates elapsed time before resuming; a delayed timer cannot extend the grace period.

## Implementation sequence

### 1. Extract a small, testable coordinator

Introduce `HeartRateCoordinator` (or equivalent) in the HR package. Keep Android APIs in the existing service/adapters. Avoid extracting unrelated AirPodsService functionality.

Have one serialized owner of session transitions. Model these separately:

- user intent: stopped / monitor-only / sharing(method), target AirPods, Gym ownership;
- sensor: stopped / waiting / live / stale / recovering / lifecycle-suspended / terminal error;
- transport: stopped / starting / ready / recovering / terminal error;
- session generation, connection token, peripheral instance token;
- original outage deadlines, attempt counts, pending start, and monitoring visibility/grace.

Inject monotonic time and scheduling/effects so tests can drive events deterministically without Android or real sleeping. Use either a reducer returning effects or a coordinator with fakeable dependencies; do not build both. Keep existing public service/ViewModel methods as thin entry points where practical. Use a service-owned scope instead of detached watchdog scopes. All emitted effects/callbacks must check their captured generation before mutating state.

Write coordinator regression tests before wiring it into the service. Remove superseded retry/deadline fields and jobs when integration is complete; there must not be two competing recovery implementations.

### 2. Fix freshness and connection identity at ingress

Carry an internal sample envelope containing BPM, monotonic arrival time, source connection identity, and captured session generation. Timestamp at the earliest practical read/parse boundary before queuing onto the main thread. Never stamp a queued event with the current generation when it is finally handled.

Bind disconnect events and read-loop cleanup to the originating connection as well. Specifically inspect cleanup after `readJob.join()`: an obsolete read loop must not reset the shared AACP manager or disconnect a replacement connection. A check only around clearing the socket reference is insufficient if subsequent reset/broadcast operations remain unconditional.

Reject stale, wrong-generation, wrong-device, wrong-connection, and out-of-order samples before updating session state or forwarding. At exactly 5 seconds a sample is expired. Wall-clock time is display metadata only. Do not deduplicate by BPM value: consecutive equal BPM readings are valid distinct samples. Do not assume a protocol sequence number is a monotonic measurement sequence without proving it from the protocol.

This protects against local queue delays; it cannot establish the true physiological acquisition time of data already buffered inside the sensor. Document that limit instead of claiming exact sensor timestamps.

### 3. Harden BLE lifecycle and delivery

- Distinguish adapter unavailable, permission denied, unsupported capability, service failure, advertising failure, and repeated notification failure.
- Wire adapter changes into the coordinator. Recreate a fresh peripheral after off/on rather than reusing stale Android objects.
- Declare readiness only after service setup and advertising success. Add a 10-second setup timeout, bounded by the outage deadline, for missing asynchronous callbacks.
- Serialize peripheral lifecycle/callback mutations or protect them consistently. Instance/generation guards must prevent old callbacks from changing current state or consuming the current retry budget.
- Permission revocation during start, service-added callbacks, reads/writes, publish, or cleanup must not escape as a crash. Do not hide publish SecurityException as an anonymous error that leaves sharing active forever.
- Keep at most one in-flight notification per receiver and no historical sample queue. If completion is missing for 5 seconds, terminate/reset that receiver connection rather than submitting an overlapping notification whose late callback could complete the wrong send.
- Count asynchronous failed completion statuses as failures, not successful delivery. Three consecutive immediate/asynchronous delivery failures for one receiver should reset that receiver connection; a service-unbound/global server failure should recreate the peripheral through coordinator recovery. Reset per-receiver failure counts on a confirmed successful send.
- On unsubscribe/disconnect, discard receiver-specific pending state. Ignore late completions without a pending current send. Verify how Android callbacks behave after reconnect; they contain no application notification ID, so do not claim perfect correlation that the API cannot supply.
- Preserve notification counters across peripheral recreation within one user session, or explicitly expose per-instance counters. Prefer cumulative confirmed sends so UI values do not unexpectedly jump backwards.

### 4. Harden TICKR and presentation

- Preserve first-real-value-before-pair, idempotent stale zero, zero-on-Stop, and saved pairing.
- Add an explicit localized TICKR reconnect action while TICKR sharing is active. It resets bridge pairing state and pairs only after a new accepted sample; no cached value is sent. Keep the same monitoring intent and target device.
- Handle failure to send a stale/stop zero explicitly: retain pending cleanup and show/log the reason without preventing other teardown.
- Broadcast acceptance is not acknowledgment of receiver delivery. UI/counters must distinguish commands sent from confirmed BLE notification completion; neither proves Wahoo displayed a reading.
- Derive monitoring text and sharing text independently. A fresh sensor BPM can be shown while BLE recovers, with sharing clearly reconnecting. Advertising with zero subscribers means available, not transmitting to a receiver.
- Update Home control, HR screen, notification, and ViewModel consistently. Stop remains accessible during recovery. Do not hide all active-session controls merely because model identification is temporarily unavailable after disconnect.
- Use localized string resources for every new label/error. Follow existing locale validation practices; do not embed raw exception messages in product UI.

### 5. Integrate lifecycle, Gym, and cleanup

Implement the monitoring-only policy above through explicit visibility/navigation events. Ensure configuration changes cannot erase intent through old-screen disposal. Keep HR consent memory-only.

Preserve method-switch behavior with one transport active at a time, Gym ownership, manual Stop suppression until Gym off/on, and manually started sessions surviving Gym off. Internal transport replacement or method-switch teardown must not accidentally be recorded as a new manual Stop. Validate the selected method/permissions before tearing down a working method where possible.

Make cleanup idempotent and best-effort across independent resources: one failed BLE close or TICKR zero must not prevent timer cancellation, notification cleanup, or wake-lock release. Do not release resources owned by another session.

## Automated acceptance tests

Use fake time/coroutine scheduling, fake sensor and sharing adapters, and existing Robolectric/MockK patterns. Do not test private fields as the main proof of correctness. Cover observable transitions, commands, and absence of forbidden effects.

| Scenario | Required assertion |
| --- | --- |
| Freshness boundaries | At 4,999 ms sample is eligible; at 5,000 ms rejected/stale. Wall-clock jumps do not affect age. |
| Queued delivery | A sample queued for over 5 seconds never becomes live or reaches either transport. Equal successive BPM values still forward as separate fresh samples. |
| Old sessions/connections | Old sample, disconnect, ownership event, BLE success/failure and retry callback cannot mutate a replacement session. |
| Short dropout | Clear/zero at 5 seconds; fresh sample before 15 seconds restores live without sensor restart. |
| Silent sensor | No initial samples and later silence both recover; successful command without samples does not complete recovery. |
| Deadline | Recovery remains bounded through repeated successful commands with no data; delayed scheduler wakeup cannot issue an expired retry. |
| Repeated outages | A fresh recovered sensor starts a new budget for the next outage. Stable BLE operation resets its failure budget; flapping does not. |
| Independent readiness | Sensor recovery cannot clear BLE recovery and BLE readiness cannot make stale sensor data live. |
| Stop races | Stop during each backoff, setup, waiting-for-first-sample, grace period and callback leaves stopped state with no later Start/pair/publish. |
| Adapter off/on | One fresh BLE instance is created on return; old subscriptions/advertising state vanish; expired sessions do not resume. |
| BLE callback timeout | Missing service/advertising callback reaches bounded recovery; missing notification completion never creates overlapping sends. |
| Receiver lifecycle | Disconnect/unsubscribe clears pending state; reconnect requires subscribe; no old BPM replay; sensor remains running. |
| BLE send errors | Permission error terminates cleanly, repeated receiver failures reset receiver, global server failure triggers bounded peripheral recovery. |
| Monitoring lifecycle | Recreation preserves intent; background suspends; timely return requires fresh sample; expired grace/navigation/process restart do not auto-resume. |
| Sharing lifecycle | Background, navigation and preview disposal leave authorized sharing running. |
| Identity/ownership | Different AirPods or confirmed different owner stops; recovery never calls takeover. |
| TICKR | Explicit reconnect pairs after next fresh value; failed zero retains cleanup flag; Stop always attempts zero; no unpair. |
| Gym/method switch | Existing ownership/suppression semantics survive recovery and switches; exactly one active transport. |
| Resource cleanup | All terminal paths release owned wake lock, jobs, server/advertiser and notification, even when one cleanup effect throws. |

Add adapter/service wiring tests as well as pure coordinator tests. Pure tests cannot prove that adapter-state broadcasts, real callback generations, or UI lifecycle events are actually wired into the coordinator.

## Build and verification commands

Run from `/Users/nikos/Everypods/android`. Java 21 is available at `/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home` on the audited machine; use the configured JDK if the environment differs. Do not print local.properties or signing secrets.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'io.automated.ventures.everypods.bluetooth.hrm.*' --console=plain
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
git diff --check
```

PR #50 has no `testRelayPreviewUnitTest` or `assembleRelayPreview` task. Its debug variant has DIRCON enabled; run the debug checks above, including DIRCON tests. Run the repository's current locale/resource validation if strings changed. Add focused service/UI test filters as appropriate; the full debug suite must include them. If a task name changes, discover the actual task before substituting it. Report unrelated existing failures separately with evidence; do not silently skip checks or expand lint baselines. If the release toolchain/signing setup is already available, also verify the minified release build; otherwise mark that check unperformed.

## Physical validation matrix

Use supported AirPods Pro 3 and a capable sender. Previous sessions demonstrated same-Pixel TICKR and Pixel-to-Xiaomi BLE; these are historical test routes, not proof for this revision. Same-phone standard BLE discovery was not established. Do not run normal EveryPods and Relay Bluetooth managers concurrently on the same test phone.

Record app build/variant, phone/OS, AirPods firmware, receiver app/version, test steps, expected/actual result, timing and relevant state-transition logs. Do not enable raw BPM logging by default. Do not clear app data or unpair devices as a test shortcut.

1. **Baseline:** both sharing methods show real changing BPM; BLE receiver subscribes and confirmed-send counter increases. Stop from HR screen, Home, and notification works.
2. **Sensor gap:** remove/reseat AirPods or otherwise interrupt sensing. Check stale display at about 5 seconds and recovery after 15 seconds. Confirm that actual sensor packet silence occurred; removal alone may not guarantee it. No synthetic/old readings on return.
3. **AirPods connection loss:** test brief loss and loss beyond 60 seconds, repeated at least three times. Brief loss recovers only to the same locally owned device; expired recovery stays stopped.
4. **Bluetooth toggle:** turn sender Bluetooth off for 10 seconds, then on. Verify sensor returns and BLE receiver can rediscover/resubscribe. Repeat three times. Repeat with off duration over the recovery budget; no automatic resume after expiry.
5. **Receiver-only interruption:** turn receiver Bluetooth off/on and restart the receiver app. Sender monitoring continues, receiver counts clear, and only fresh post-subscription samples are delivered.
6. **Wahoo restart:** restart Wahoo while TICKR sharing continues. Observe whether saved pairing resumes; exercise explicit reconnect if required. Verify new BPM and Stop zero. Do not infer success from sent-broadcast counts.
7. **Lifecycle:** rotate/recreate Activity, lock/unlock, background/foreground and navigate away/back. Exercise monitor-only grace before/after 60 seconds and sharing independence. No duplicate sensor start or lingering background monitor-only stream.
8. **Stop races:** press Stop while reconnecting, while Bluetooth is off, immediately after Bluetooth returns, and during monitor resume. No subsequent restart or re-pair.
9. **Ownership/device:** transfer AirPods to another device; session stops without takeover. A different pair must never inherit the session.
10. **Gym/method switch:** verify manual versus Gym ownership, suppression, and switching TICKR/BLE during healthy operation and recovery.
11. **Permissions/process:** revoke relevant permission and check clean termination. Kill/restart the app/service and verify no stream resumes, pending TICKR zero is attempted, and stale notification state is removed. Distinguish ordinary process death from force-stop in the report.
12. **Soak:** at least 30 minutes for each transport, screen locked, with several deliberate dropouts. Confirm counters/retry budgets recover, no stuck reconnecting status, and Stop releases resources. Smoke-test audio, EQ, gestures, Gym and handover afterward.

For BLE stale-value behavior, record how long the receiver continues displaying its last reading during a gap. EveryPods cannot guarantee another app's display policy. Do not add fabricated zero BPM or an unverified sensor-contact flag to disguise this. If the receiver holds stale values indefinitely, document it and propose a separately validated disconnect-on-stale policy rather than silently changing protocol semantics.

## Completion and handoff requirements

- Deliver the code, meaningful new regression tests, and an updated `docs/heart-rate-sharing-release.md` describing actual policies.
- Add `docs/heart-rate-resilience-validation.md` with exact commands/results, automated test totals, physical-test results, and explicit pending checks.
- Ensure git diff contains only intentional HR-related changes. No APKs, secrets or device logs committed.
- Final report: changed behavior, implementation files, test results, device validation evidence, unresolved limitations, and any deviations from this specification with reasons.
- Do not claim end-to-end completion if device tests were unavailable. Say “implementation and automated validation complete; device validation pending” and list the exact remaining matrix rows.
- Stop once the authorized implementation/validation is complete; do not publish or merge as an assumed final step.
