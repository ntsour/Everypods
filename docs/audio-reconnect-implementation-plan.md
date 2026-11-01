# Agent implementation plan: unified AirPods audio reconnection

Prepared 31 October 2026. Status: implementation in progress on `codex/unified-audio-reconnect`; see `docs/audio-reconnect-validation.md` for verified scope and remaining acceptance gates.

## Mission and intended outcome

Implement the design in `docs/reconnect-protocol-design.md`. Replace independent A2DP connection/retry paths with one serialized, outcome-driven coordinator. Preserve working peer transport, AirPods control-channel functionality, explicit playback/call/manual takeover behavior, and normal app packaging.

Expected behavior: a valid case-open/ear-in episode reconnects the eligible last device without requiring playback, online and when all configured peers are unreachable. A failed first profile attempt can recover within a bounded episode. Old callbacks cannot reconnect after cancellation. Observing another device's connection produces no moved-away notification.

Do the implementation and automated verification autonomously. If physical actions are required, finish all independent work and provide a short exact test sequence. Do not claim physical success without device evidence/user confirmation. Do not merge or publish a release as part of this handoff.

## 0. Establish a safe baseline

Repository: `/Users/nikos/Everypods`. Observed HEAD: `05b05a5b51ead74b50e1952d8e49661ace2b3fdf`, branch `codex/head-gesture-speed-tuning`. Verify again before starting; these are pointers, not instructions to reset the checkout.

The working directory contains unrelated uncommitted head-gesture changes, localization changes and test changes. Do not stage, overwrite, stash wholesale or revert them. Inspect applicable AGENTS.md and current Git status. Use an isolated worktree/branch `codex/unified-audio-reconnect` from the current appropriate committed base; preserve newer committed fixes. Copy this plan, its design document and needed sanitized trace fixtures into the worktree because these documents/log captures may be untracked. Never copy all untracked work wholesale.

Read:
- `docs/reconnect-protocol-design.md`
- `docs/case-connection-history.md`
- `docs/bt-handover-sync.md`
- `docs/connection-hardening-validation.md` (historical validation, not a current guarantee)
- `tmp/case-open-review-2026-10-31/analysis.md`, `pixel-timeline.txt`, `xiaomi-timeline.txt`.

The existing `ConnectionEngine` controls L2CAP/AACP, not A2DP audio. Do not replace it or reuse its connected state as proof of local audio ownership. Android-only scope; no iOS target, heart-rate, Wi-Fi relay, gesture or UI redesign work.

## 1. Capture the contracts and tests first

Create a migration inventory listing every caller of `connectAudio`, `disconnectAudio`, `takeOver`, passive acquisition, profile state broadcasts and delayed retry callbacks. Classify each as passive proximity, playback, call, manual, explicit release or profile reconciliation. Include `MediaController`, BLE and AACP callbacks, connection broadcasts and any indirect manual/notification actions.

Relevant files, under `android/app/src/main/java/io/automated/ventures/everypods/`:

| Area | Existing source | Intended change |
|---|---|---|
| Audio orchestration | `services/AirPodsService.kt` | Submit events and execute adapter effects; remove competing audio retry authorities |
| Case/ear evidence | `bluetooth/BLEManager.kt` | Supply timestamped, source-aware evidence; silence remains unknown |
| Playback intent | `utils/MediaController.kt`, `utils/TakeoverAttemptFence.kt` | Submit explicit intents; preserve autoplay restrictions and generation fencing |
| Policy and last owner | `utils/AudioLeasePrefs.kt`, `presentation/viewmodel/AirPodsViewModel.kt` | One observable policy source, write origins and revisions |
| Existing passive rules | `utils/LidAutoconnectPolicy.kt`, `utils/CrossDeviceReconnectPolicy.kt` | Migrate needed rules into the coordinator; remove redundant scheduling decisions |
| Peer adapters | `utils/CrossDevice.kt`, `utils/CrossDeviceClient.kt` | Exchange state/grants through one coordinator-facing interface |
| Peer state | `utils/peer/PassiveAcquisitionCoordinator.kt`, `PeerAcquisitionProtocol.kt`, `PeerOwnershipStore.kt`, `PeerSnapshotRevision.kt` | Reuse and adapt safely; maintain session, revision, reservation and unresolved-command protection |
| Diagnostics | `utils/LidAutoconnectDiagnostics.kt`, `utils/CaseConnectionJournal.kt` | Episode IDs, decisions, timing and terminal outcomes; retain 48-hour history |

Add sanitized fixtures to test resources, carrying relative times and symbolic device/headset IDs. Do not commit complete logcat archives or personal app activity. Fixtures must encode these actual observations:

| Recorded scenario | Required regression behavior |
|---|---|
| Pixel 30 Oct 22:20: scheduled 400 ms callback ran about 88 s late | No blind stale connect. Reassess only if new valid evidence exists; record scheduling lag and expiry reason. |
| Pixel 22:31: accepted -> CONNECTING -> DISCONNECTED after about 21 s | Acceptance is not success. Observe the unresolved command; after definite failure, evaluate a controlled retry if current evidence permits. |
| Pixel 23:28: similar failure after about 16 s | Retry is driven by final profile transition, not consumed entirely by early timer callbacks. |
| Pixel 23:24: first callback delayed about 9.7 s, connected about 11 s after lid detection | Do not discard valid completion solely because an early timer elapsed. |
| Xiaomi historical `feature=false`, current saved preference true | Record observation and later policy change without inventing cause or turning settings on automatically. |
| Both devices report connected during overlapping intervals | Preserve contradictory evidence; do not infer audible ownership or authorize a grab just from a last-owner flag. |
| All peers unreachable | Only eligible last holder may use bounded offline recovery; reconnecting peers force reassessment. |

Exit: baseline tests run, inventory written, new failure-oriented tests exist and demonstrate the relevant deficiencies or define the new module's contract.

## 2. Implement a pure coordinator and typed model

Suggested new package: `bluetooth/audio/`. Names may change if existing conventions justify it; responsibilities must remain explicit.

- `AudioConnectionCoordinator`: serialized state machine with injected monotonic clock, deterministic timers and effect sink; no Android classes in core logic.
- `AudioConnectionState`, `AudioConnectionEvent`, `AudioConnectionEffect`: typed states, events and effects.
- `AudioConnectionPolicy`: immutable snapshot of user policy and temporary constraints, with revision/origin.
- `AudioConnectionEvidence`: profile, route, case/ear and peer observations with freshness and source.
- `AudioConnectionConfig`: centralized, documented retry/timing parameters.

Suggested interface: dispatch one event -> update state -> emit effects such as QueryProfile, RefreshPeerState, BeginReservation, ConnectProfile, CancelTimer, RecordOutcome. Effects return events; no effect recursively bypasses the event queue. Use a coroutine actor or equivalent single-consumer serialization. UI/main-thread work is an adapter concern.

States: Idle, Assessing, Coordinating, Connecting, Verifying, RetryWait, Connected, Cancelled, Failed; include explicit unresolved/reconciliation state if needed. Each attempt has headset ID, intent ID, process/adapter generation, trigger, episode generation, monotonic timestamps and bounded command count.

Mandatory invariants:
- One unresolved OS connection command per headset. Superseding intent invalidates future effects but does not erase the old command's unresolved OS state.
- Missing profile proxy, permission failure and query errors mean unknown; never synthesize connected or disconnected.
- Fresh A2DP connected evidence is audio-profile success; AACP success alone is insufficient. Active audio-route verification is a separate result. No active playback is required for successful passive connection.
- Confirmed disconnected with no conflicting control/transition evidence may be idle for peer acquisition. Preserve current call protections.
- Recheck policy, current intent, evidence and grants immediately before issuing the actual Android command, including delayed service-proxy callbacks.
- Retry only after definite failure or safe reconciliation. Do not call connect repeatedly while CONNECTING.
- Old completion broadcasts may lack attempt IDs: match headset and adapter generation, then reconcile current profile state; never pretend broadcasts carry a trustworthy request token.
- Cancellation, adapter loss, process restart and grant expiry cannot silently turn an unresolved command into released ownership.

Use at most two actual OS connect commands per passive episode initially. Keep intent freshness, coordination timeout, platform attempt timeout and overall execution bound separate. Choose provisional values from the captured 16–21 second attempts; document rationale and make them injectable. Longer timeouts alone are not a fix. Unresolved timeout must terminate active work with an accurate outcome while blocking unsafe overlapping commands until reconciliation.

Exit: deterministic state-machine tests pass for races, cancellation, retries, late callbacks and terminal outcomes.

## 3. Unify settings and case evidence

Audit every write to the case-open key, including initialization/migration, UI, relay pause/restore and peer setup defaults. Reuse existing storage; no forced reset or automatic opt-in migration.

Provide one application-scoped policy repository observed by UI and coordinator. Include persisted requested state, effective state, override reason and policy revision. Journal change origin (UI, migration, relay, external refresh, initialization), previous/new effective values and the revision consumed by an attempt. Do not assert a stale cache defect without reproducing it. Test process recreation and independently arriving settings updates.

Model case state as open/closed/unknown, distinct from both buds stored/out/unknown. BLE silence must not become closed. Closed empty case can retain a fresh removal intent; both buds positively stored cancels it. Deduplicate repeated open advertisements and stale scan batches into episodes so they do not reset retry budgets indefinitely.

Exit: UI and controller consume the same policy revision; case event replay tests prove no fabricated close, no endless episode extension and no automatic setting changes.

## 4. Connect the Android adapter and scheduling lifecycle

Add an Android audio-profile adapter owning profile queries and connect/disconnect reflection. Respect the existing permission and non-rooted Xiaomi constraints. Keep hidden API handling encapsulated; do not introduce root/Xposed requirements or reflection hacks elsewhere.

Move reconnection decisions off main-handler timers. After receipt of a valid event, use a bounded execution mechanism appropriate for the existing foreground service and Android target. Verify current platform requirements before changing wake-lock/foreground-service behavior. If using a short partial wake lock, acquire only for active work, with a hard timeout and finally/onDestroy release. It cannot repair an event that Android never delivered; journal event-receipt lag separately. No permanent wake lock or endless scan/reconnect polling.

Replace fixed early retry fan-out with profile-outcome-driven scheduling. A CONNECTING -> DISCONNECTED event should reach the coordinator while the episode is still observable. On delayed callback, expiry alone must not issue a command; new fresh case/ear evidence may create a new eligible intent.

Exit: adapter tests show delayed callbacks cannot issue stale commands, resources are released, and process/adapter restarts reconcile rather than replay stale requests.

## 5. Integrate peer arbitration without weakening transport safety

Reuse authenticated identities, HELLO/session binding, strict frame schemas, heartbeat nonce validation and ownership revisions. Do not bypass grants for a reachable-but-unsynchronized peer.

Expose peer observations, reservation state and outcomes to the audio coordinator. Maintain separate ownership revision and reservation phase. A denied request must carry/log a specific reason. Distinguish REQUESTED, GRANTED/START_ACK, OS_COMMAND_ISSUED and final observed outcome.

Tie grants to one attempt and validate immediately before the OS command. Duplicate messages are idempotent. Membership/session changes invalidate pre-command work. After command dispatch, retain unresolved protection until actual outcome or reconciliation; a lost acknowledgement is not proof of cancellation.

Before adding wire fields, inspect `PeerV2Message` exact-schema validation and capability negotiation. Preserve existing protocol behavior or introduce negotiated capability/version changes with mixed-version tests. Do not silently break older peers. Add bounded request/state refresh; avoid nonce bypasses or unbounded snapshot ping loops.

Offline mode: all configured peers unreachable, remembered local holder, fresh local evidence, no credible contrary ownership. No reachability guarantee exists; keep the partition tradeoff explicit. On peer return, reconcile before further commands. Simultaneous same-priority intents with live communication need deterministic arbitration; define and test call/manual/media/passive precedence while preserving existing explicit takeover semantics. An actual active call must not lose audio merely because another device rings. A passive event must never steal active audio from a confirmed peer.

Exit: two-coordinator message simulations pass for duplicate/out-of-order packets, peer restart, partial partitions, simultaneous intents and returned peers. Use production protocol adapters in the harness.

## 6. Migrate all callers and remove duplicate authority

Route each inventory entry through the coordinator, preserving its trigger semantics. Include explicit release actions so a delayed release cannot disconnect a newer connection. Preserve no-autoplay for passive acquisition and existing explicit playback replay behavior.

Remove or delegate superseded `pendingLidAutoconnectRunnable`, passive recovery timers, flux/retry fan-out, takeover retries and outcome watches only once their responsibilities are covered. Retain the separate L2CAP engine and peer transport recovery loops. Do not leave old and new audio schedulers concurrently issuing commands.

During migration, diagnostics-only shadow evaluation is acceptable, but it must emit no connection effects. Final runtime must have exactly one audio command authority. Verify through an automated architecture check plus source search for profile connect/disconnect reflection and all adapter callers.

Notification policy: emit a departure only when local evidence establishes the source held audio and the transition is a departure. Peer-observer updates remain silent. Profile-connected, route-verified and control-channel-connected must not collapse into one misleading status.

Exit: every trigger integrated, no duplicate audio command paths, no unrelated UI/HR/relay regressions.

## 7. Diagnostics, verification and delivery

Extend existing journal; keep 48-hour retention. Include episode/attempt IDs, monotonic/wall times, received-vs-executed lag, policy revision, profile-proxy generation, peer session/revision, grant reason, OS command result, actual profile transitions, route evidence, power-state context, retry cause and terminal outcome. Redact device identifiers consistently. Platform failure details may be unavailable; record that explicitly rather than inventing a controller reason.

Produce a small offline analyzer that groups events by episode and emits a timeline/result table. BLE observations alone are not physical case-opening counts. Test analyzer results against the sanitized traces.

Run appropriate tests incrementally, then the final matrix from `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Run localization validation if resources change, using the current repository task. Run `git diff --check`. Use one Gradle invocation at a time per checkout; concurrent runs previously collided in test output files. Report actual counts/results, not historical counts from this plan. If changing common production code, check applicable release/Play compilation and lint tasks after inspecting the current build variants; never assume the removed relayPreview variant exists. No release upload/signing changes.

Physical verification uses Pixel 10 and Xiaomi 24091RPADG. Discover current devices/addresses and installed packages; do not assume saved ADB ports remain valid. Preserve pairing, user settings and permissions. Install the same normal-ID debug build on both and record APK hash, version and Git revision. Do not claim installation validates behavior.

| Scenario | Minimum exercise | Required observation |
|---|---|---|
| Both peers online, each last holder | 10 case/removal cycles per device | Eligible device connects without playback; observer stays quiet |
| Other device Bluetooth off | 5 cycles per last holder | Bounded offline recovery works; no request waits forever for missing peer |
| Screen off/device idle | 5 cycles per device after real idle period | Record event/dispatch/connection latency; no stale-command execution |
| Close empty case after removal / buds stored | 3 of each per device | Removal can reconnect; stored buds cancel |
| First connection fails | Deterministic simulation, physical if reproducible | No duplicate command while connecting; controlled retry after definite failure |
| Peer coordination lost but peer retains audio | Simulated plus controlled physical case | Correct uncertainty reporting; no repeated ownership tug-of-war |
| Concurrent playback/manual/call intents | Simulated matrix plus physical feasible cases | Documented precedence, actual active-call protection, no oscillation |
| Adapter off/on, process death, late proxy callback | Simulated and physical lifecycle checks | Fresh reconciliation; no replayed stale command or resource leak |
| Existing functions | Playback handover, ear detection, call handling, HR/relay smoke checks | Existing behavior remains usable |

Record each result with timestamps, source/destination, starting state, expected/actual outcome and journal reference. Report all failures and latency distributions. Require no unexplained failed connection in the controlled online/offline case cycles before calling physical acceptance passed. Hardware-unavailable conditions must be reported distinctly. Do not manufacture interaction or wait indefinitely for user actions: provide exact remaining tests and mark physical validation pending.

Deliver:
- Scoped implementation and meaningful regression/integration tests.
- Updated protocol documentation plus migration inventory.
- `docs/audio-reconnect-validation.md` with exact automated and physical results and remaining limitations.
- Analyzer and sanitized evidence fixtures.
- A final summary separating implementation done, automated checks passed, and physical acceptance passed/pending.

Prepare reviewable commits on the isolated branch. If the user's execution instruction includes publishing a PR, create it against the verified base and attach it to the chat. Do not merge automatically. Do not stop at a design-only skeleton, fake-only happy path or increased timeout; complete adapters, migration, diagnostics and verification within the authorized implementation task.

## Ready-to-use agent assignment

Implement `docs/audio-reconnect-implementation-plan.md` and the companion `docs/reconnect-protocol-design.md` in EveryPods. Start by inspecting current instructions and preserving the existing unrelated head-gesture work. Build one outcome-driven audio coordinator, migrate every audio connection entry point to it, retain peer/session safety and offline last-holder recovery, and add regression tests from the captured Pixel/Xiaomi failures. Complete the automated gates and document exact physical tests/results separately. Do not claim the protocol works on hardware based only on tests or installation. Follow the staged plan through integration and cleanup; do not stop after creating the core state machine. Report evidence and unresolved limitations.
