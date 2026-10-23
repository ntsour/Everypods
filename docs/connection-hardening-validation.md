# Connection hardening validation record

Status as of 2026-10-23. Automated results prove code and build behavior only.
Full radio recovery and workout endurance acceptance remains pending.

## Current combined build (2026-10-23)

- Normal `debug` now includes Wi-Fi audio relay and enables the DIRCON test feature;
  `relayPreview` has been removed. Release variants retain the DIRCON feature gate.
- `:app:testDebugUnitTest :app:validateLocalizationResources :app:lintDebug
  :app:assembleDebug`: passed, 338 tests, zero failures/errors/skips. Lint passed
  with the existing baseline and 263 warnings plus one hint.
- The resulting normal-ID APK (`io.automated.ventures.everypods`) was installed
  on Pixel 10 and Xiaomi 24091RPADG; installed APK SHA-256 matched the build on both.
- One-sided BT creation/removal and offline removal across restart were verified
  on those devices; see `bt-handover-sync.md` for the precise evidence and limits.
- Case artwork tests cover monitoring/sharing combinations, selected transport,
  recovery, and suppression of stale readings. The final artwork has not been
  visually verified on an unlocked Pixel.
- Long-running DIRCON recovery, repeated return-to-range, and audio contention
  remain pending. Earlier Zwift/Incyclist compatibility does not validate the new
  recovery implementation.

The table below records the original hardening checks; its pending radio and
workout scenarios remain pending unless explicitly superseded above.

| Scenario | Revision | Devices/app versions | Expected result | Actual result | Evidence | Status |
|---|---|---|---|---|---|---|
| Peer ownership store: unknown peers, expiry, session/revision, invalidation | Working tree | JVM unit tests | Missing or stale peer data remains UNKNOWN | Passed | `PeerOwnershipStoreTest` | Automated passed |
| Passive acquisition quorum, grant/start deadlines, unresolved reservations, duplicate START | Working tree | JVM unit tests | No headset command without a fresh matching reservation | Passed | `PassiveAcquisitionCoordinatorTest`, `PeerAcquisitionProtocolTest` | Automated passed; physical contention pending |
| Simultaneous v2 duplicate-link ordering policy | Working tree | JVM unit tests | Both endpoints select the same winning link | Policy passed | `PeerConnectionArbitrationTest` | Automated passed; physical simultaneous-connect test pending |
| EPH2 byte splitting/coalescing, malformed headers, payload bounds | Working tree | JVM unit tests | Incremental decode; malformed input closes only that peer | Passed | `PeerV2FrameTest` | Automated passed |
| Strict v2 payload schemas and malformed JSON | Working tree | JVM unit tests | Reject invalid identities and schemas before dispatch | Passed | `PeerV2MessageTest` | Automated passed |
| Retry bounds, identity persistence, capabilities, duplicate-link arbitration | Working tree | JVM unit tests | Bounded retries and stable configured-peer identity | Passed | peer JVM suite | Automated passed |
| Global RFCOMM dial serialization, connect deadline, socket teardown | Working tree | Fake socket/JVM tests | One dial at a time; timeout and Stop close the exact socket and queues | Passed | `PeerConnectionSupervisorTest`, `PeerConnectDeadlineTest`, `PeerConnectionTest` | Automated passed; Android radio behavior pending |
| Peer heartbeat and READY synchronization | Working tree | JVM unit tests | Matching nonce plus accepted snapshot is required for v2 READY | Passed | `PeerHeartbeatTest`, v2 session code | Automated passed; radio loss/recovery pending |
| Listener endpoint shutdown and accepted socket ownership | Working tree | Source review; Android Bluetooth callback not mocked | Stop closes listener and accepted sockets and cancels child keep-alive | Implemented; not observed on device | `CrossDevice.kt` endpoint generation and accepted-socket registry | Agent-reviewed; device test pending |
| Peer removal through settings reconciliation | Working tree | Source review plus full JVM suite | Removed peer transport and ownership are invalidated | Implemented in `CrossDevice.init` | `CrossDevice.kt` membership reconciliation | Agent-reviewed; device test pending |
| DIRCON retry bounds/jitter, Stop generation invalidation and stale callbacks | Working tree | JVM unit tests | Old transport/network callbacks cannot restart or overwrite current state | Passed | `DirconRetryPolicyTest`, `DirconSessionControllerTest` | Automated passed |
| Wi-Fi network selection, local IPv4 requirement and network changes | Working tree | JVM unit tests | Use a suitable Wi-Fi IPv4 endpoint and wake on network return | Passed | `DirconNetworkMonitorTest` | Automated passed; physical Wi-Fi recovery pending |
| NSD registration deadline and Stop-before-registration race | Working tree | JVM unit tests | Late successful registration is unregistered exactly once | Passed | `DirconNsdRegistrationLeaseTest` | Automated passed |
| DIRCON request budget: 100 requests/second, burst 200 | Working tree | Fake elapsed-clock unit tests | Enforce burst/refill bounds and reject clock regression | Passed | `DirconRequestRateLimiterTest` | Automated passed |
| Typed share intent and independent sensor/transport phases | Working tree | JVM unit tests | Sensor and transport recovery stay independently visible | Passed | `HeartRateSharingStateReducerTest`, controller snapshot assertions | Automated passed |
| DIRCON loopback subscribe/ack ordering, measurements, unsubscribe, Stop, client isolation | Working tree | Loopback TCP tests | Ack precedes samples; unsubscribe prevents later samples; no cached replay | Passed | `HeartRateBroadcasterTest` | Automated passed |
| DIRCON blocked writer and bounded response-queue overflow | Working tree | Loopback TCP fault tests | Slow receiver closes while healthy receiver continues | Passed | `HeartRateBroadcasterTest` | Automated passed |
| DIRCON four-client cap and 100 sequential cleanup cycles | Working tree | Loopback TCP tests | Reject fifth receiver; registry returns to zero per cycle | Passed | `HeartRateBroadcasterTest` | Automated passed |
| DIRCON partial-frame drip-feed deadline | Working tree | Loopback TCP and injected monotonic clock | Deadline remains measured from first byte | Passed | `HeartRateBroadcasterTest` | Automated passed |
| Passive cancellation after headset command is issued | Working tree | JVM unit tests | Unresolved in-flight reservation blocks replacement until ownership resolves | Passed | `PassiveAcquisitionCoordinatorTest` | Automated passed |
| Passive cancellation before headset command is issued | Working tree | JVM unit tests | Safe unstarted reservation can be canceled | Passed | `PassiveAcquisitionCoordinatorTest` | Automated passed |
| Diagnostic event redaction | Working tree | JVM unit tests | No peer addresses or packet payloads in diagnostics | Passed | `ConnectionDiagnosticsTest` | Automated passed |
| Pixel ↔ Xiaomi return-to-range and audio contention | Pending | Pixel 10 + Xiaomi; app versions to record | At least 20 cycles per direction, zero unwanted takeover, report latency distribution | Not run | Physical matrix in implementation handoff | Pending user test |
| Wi-Fi loss/recovery, receiver re-subscription, two-hour DIRCON session | Pending | Pixel 10 + Incyclist/Zwift versions to record | Discoverable after Wi-Fi return; record whether receiver requires manual re-pair | Not run | Physical matrix in implementation handoff | Pending user test |
| Stale sensor, owner handover, permission revocation, Stop races | Pending | Pixel 10 + AirPods Pro 3; app version to record | No stale/synthetic BPM or post-Stop advertisement/data | Not run | Physical matrix in implementation handoff | Pending user test |

## Earlier implementation command record

- `:app:testDebugUnitTest`: passed, 304 tests, 0 failures/errors/skips (2026-10-23).
- `:app:testRelayPreviewUnitTest`: passed, 339 tests, 0 failures/errors/skips (2026-10-23).
- `:app:assembleDebug :app:assembleRelayPreview :app:assembleRelease`: passed;
  normal Release R8 minification completed (2026-10-23).
- `:app:lintDebug :app:lintRelayPreview :app:lintRelease`: passed with no
  blocking errors. Reports contain 255 Debug/Release warnings and 258
  relayPreview warnings, with the existing lint baseline applied (2026-10-23).
- `python3 scripts/validate_localization.py`: passed for 11 locale folders
  (2026-10-23).
- `git diff --check`: passed (2026-10-23).

The final combined unit/build/lint run includes the blocked-write deadline,
bounded queue overflow, four-client cap, 100-cycle cleanup, partial-frame timing,
request-rate limiter, late NSD callback cleanup, peer connect deadlines and
heartbeat synchronization. Physical callback behavior, radio recovery, screen-off
operation, audio contention and receiver-app recovery remain unverified. At that earlier checkpoint no APK
was installed and no physical test was performed; see the current combined build
record above for subsequent installation and BT synchronization checks.

Do not treat the earlier prototype's Incyclist/Zwift reception as validation of
this recovery implementation.
