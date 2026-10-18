# Plan: make experimental HRM work using the verified LibrePods baseline

## Objective and baseline

Continue on `codex/librepods-eq-airpods5` in the active worktree. Preserve committed EQ/AirPods 5 changes and current uncommitted HRM work, including the release protobuf keep rule. This document is a plan; implementation has not begun.

The user confirmed HRM works in the official LibrePods Android rewrite CI release APK built from `e4218c272f2388b50b6a03f4947a7e1ca962d45b` (run 38899202347, 16 October 2026), installed alongside EveryPods on the same Pixel 10 with the same AirPods Pro 3. Reported device model is A3063; firmware version3 is 9442752.

Working runtime evidence is retained outside Git in `/Users/nikos/Everypods-qa-artifacts/librepods-nightly-e4218c2/`. Detailed captures remain local. The compact `hrm-comparison.txt` omits device addresses, keys and measured BPM.

## Findings

| Area | Working LibrePods | Current EveryPods | Implication |
| --- | --- | --- | --- |
| Connect packet | `00 00 04 00 01 00 03 00 00 00 00 00 00 00 00 00` | Same packet, offset 6 is `02` | Strongest concrete difference to test first; exact meaning of that field is not established |
| HRM Start | service 84, interval 1,000,000 microseconds | Identical bytes | Do not replace the working command encoder on speculation |
| HRM Stop | Same selected service, zero interval | Matches | Retain |
| Source capabilities | `D7 00 00 00 00 00 00 00` | Matches | Retain |
| Notification request | All notifications | Matches | Retain |
| Country-code opcode 0x29 | `00 FF FF FF FF FF FF FF` | Already sent via `sendSomePacketIDontKnowWhatItIs()` | Rename/document if touched; missing packet is not the explanation |
| Initialization timing | First sequence spaced by 200 ms; repeated after completion plus 3 seconds | Initial burst, repeat after first peer response, another delayed sequence at 5 seconds | Secondary candidate; test separately from connect byte |
| Measurement decode | Services 19/20, 18 bytes, final status zero, unsigned byte 1 | Matches, with additional rejection of zero | Retain unless real frames demonstrate a mismatch |

Exact HRM Start written by both apps:
`04 00 04 00 17 00 00 00 10 00 0D 00 42 0B 08 54 10 02 1A 05 01 40 42 0F 00`

Working LibrePods logs show 77 SensorDataWX envelopes between Start at 13:41:23.461 and Stop at 13:42:40.122, with first arrival at 13:41:23.518. These are envelope counts, not a claim that every envelope is a valid HRM measurement. The user confirmed the live display works.

The previously ported revision `5dd8446` is nine commits after `e4218c2`; those commits contain no HRM/protocol/device changes. The choice of pinned revision does not explain the failure.

The successful same-phone test makes the earlier root/platform-restriction explanation a poor working hypothesis. No root, VendorID modification or health permission is needed for this experiment.

## Implementation sequence

### 1. Add enough evidence to distinguish activation from parsing

- Track current-connection Buddy frame count, descriptor, protobuf message kind, sequence, service ID, accepted samples and rejection counts.
- Recognize and report start/command acknowledgments separately from measurements. An acknowledgment must never produce Live status.
- Log command write result and selected firmware/service; session-end summary includes counts and reason.
- Keep BPM and raw measurement bytes behind temporary local validation logging. Do not log proximity keys or device identifiers in new diagnostics.
- Reset counters and parser buffers on disconnect; preserve the current malformed/split/consecutive-frame protections.

### 2. Isolate the connect-packet difference

- Update the existing AACP connect packet's offset-6 byte from `02` to the working upstream `03`, with upstream attribution and a golden-wire regression fixture.
- Use the same encoder at every existing handshake send site; avoid contradictory packet variants on a connection.
- First diagnostic candidate changes this wire field only, plus diagnostics. Keep current initialization timing so the hardware result can isolate this difference.
- Build a signed optimized release update, stop LibrePods, install EveryPods without clearing data, and establish a fresh AACP connection.
- Start manually while earbuds are worn. If valid samples arrive, retain the narrow change and proceed to acceptance checks.
- Treat the field change as a connection-protocol compatibility change requiring audio/head gesture/EQ/handover regression checks; do not assume older models are unaffected.

### 3. Align initialization only if step 2 still fails

- Compare the captured first/second initialization sequences with the working LibrePods trace.
- Use a connection-scoped initialization job: connect, capabilities, notification request, country-code packet, magic keys, with the observed 200 ms gaps and one repeat after completion plus 3 seconds.
- Keep EveryPods' existing ownership, takeover, handover and first-peer-response gate. A socket response and completed initialization are separate conditions; neither implies a sensor sample.
- Track initialization completion for the current socket. Keep Start disabled until initialization is ready, with a localized preparing-connection explanation. Require an explicit Start afterwards; never queue a request onto a replacement socket.
- Cancel initialization/repeats on disconnect or device change. Guard delayed callbacks against stale socket/connection IDs.
- Audit existing head-tracking start/stop calls and delayed handshakes for interference during an HRM session. Do not remove gesture behavior without evidence.
- Do not send repeated handshakes from Start on an already-ready connection or automatically retry other sensor services.

### 4. Keep the existing product behavior

- Retain service-owned state, capability gating, Start/Stop, live BPM, last update, five-second stale state and fifteen-second no-data timeout.
- Stop and clear on navigation, backgrounding, disconnect and device change; never automatically restart after return or reconnect.
- Retain the generated protobuf message keep rule and validate the optimized APK, since debug tests missed the earlier release-only crash.
- Localize any added initialization/error text in existing supported locales.

## Validation and acceptance

- Add focused tests for the connect packet and any acknowledgment/diagnostic classification.
- If initialization changes are necessary, test exact order/timing with a controllable scheduler, cancellation, same-socket checks, readiness, and prevention of Start before readiness.
- Run existing HRM command/unsigned decoding/frame/session tests; preserve unchanged head-tracking dispatch.
- Build debug and release; exercise protobuf encode/decode in the optimized release on Pixel.
- Run one app at a time. Stop LibrePods without uninstalling or clearing either app.
- Success requires multiple real incoming HRM samples in EveryPods, with decoded BPM matching its display. Save a local timestamped trace and screenshots; command write or ACK alone does not count.
- Verify explicit Stop and navigation send interval zero for the selected service, clear BPM, end incoming streaming, and do not restart it. Check background, disconnect/reconnect and device change.
- Verify ordinary audio, head gestures, EQ/readback, handover, and normal reconnection. A connection-level handshake change also needs another available supported model before broad compatibility is claimed; report unavailable checks honestly.
- If step 2 fails, record its evidence before trying step 3. If both fail, compare real inbound frames and connection replies with the working trace rather than enabling unrelated services or changing root settings.
- No commit or push is included until requested.

## Source references

- Working build: https://github.com/librepods-org/librepods/actions/runs/38899202347
- Connection initialization and connect packet: https://github.com/librepods-org/librepods/blob/e4218c272f2388b50b6a03f4947a7e1ca962d45b/android/core/src/main/kotlin/me/kavishdevar/librepods/core/bluetooth/aacp/AACPManager.kt
- Sensor command encoding: https://github.com/librepods-org/librepods/blob/e4218c272f2388b50b6a03f4947a7e1ca962d45b/android/core/src/main/kotlin/me/kavishdevar/librepods/core/bluetooth/aacp/RTBuddyManager.kt
- Activation/service selection: https://github.com/librepods-org/librepods/blob/e4218c272f2388b50b6a03f4947a7e1ca962d45b/android/core/src/main/kotlin/me/kavishdevar/librepods/core/devices/AppleDevice.kt

## Implementation outcome (17 October 2026)

Steps 1 and 2 implemented and installed as a signed release update. Live readings
now work with the existing initialization timing, so step 3 was not needed.
The firmware parser also has explicit coverage for numeric firmware `9442752`
and leading whitespace; its regex now uses an unambiguous Kotlin raw string.
The full unit suite passed: 176 tests, including 16 HRM tests. Debug and release
builds passed. Two timestamped UI values matched real incoming BPM, and Stop,
navigation and actual background/return checks passed. Detailed measurement
logging was disabled afterwards. Changes remain uncommitted.

The user also confirmed normal audio, head gestures and handover work after the update. Physical EQ adjustment/readback and additional headset models were not rechecked in this pass.
