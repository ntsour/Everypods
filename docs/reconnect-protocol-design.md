# Unified audio reconnection protocol

Status: proposed design, 31 October 2026. This document does not implement or validate a new protocol. Evidence: `tmp/case-open-review-2026-10-31/analysis.md` and both captured device timelines.

## Objective

Case opening or taking the buds out should reconnect the eligible last device without requiring playback. Playback, calls and manual reconnect must use the same audio-connection controller with explicit intent priorities. A peer that was not the source must remain quiet. Each unsuccessful attempt must end with an accurate reason and a useful recovery decision.

Keep the existing authenticated peer transport, identity/session checks and reservation mechanism. Keep the L2CAP ConnectionEngine for the separate AirPods control channel. Introduce one AudioConnectionCoordinator for A2DP connection requests and outcomes; do not confuse control-channel connectivity with audio ownership.

## Facts and policy

Keep these separate:

- Local profile evidence: disconnected, connecting, connected, disconnecting, or unknown; target headset, observation time, Bluetooth adapter generation and profile-proxy generation.
- Local route evidence when obtainable: active output for media/call. Profile connected does not by itself prove audible playback.
- Control channel connectivity: useful for commands, not proof that local audio owns the headset.
- Last confirmed owner: a preference for passive reconnection, not current ownership or a distributed lock.
- Peer reports: session-bound, headset-bound, revisioned and expiring; missing reports are unknown, not released.
- User policy: persisted value, effective value, revision and reason for any temporary override. UI and coordinator observe one policy source. Log every policy write with its origin; do not override an explicit opt-out.

The historical Xiaomi service read `feature=false`; its current saved preference is true. This is not proof of who changed it or of stale preference caching. Instrument the policy boundary before choosing a cause.

## One state machine

Idle -> Assessing -> Coordinating (when peers are reachable) -> Connecting -> Verifying -> Connected.

Connecting -> RetryWait -> Assessing on a definite failed profile transition while current intent remains eligible. Any state may become Cancelled or Failed with a specific reason. Unknown OS state enters reconciliation, not a concurrent new connect attempt.

Every case/ear/playback/manual intent carries an ID, headset, trigger, generation, monotonic event time, expiry and evidence. All triggers submit events; only the coordinator may issue the audio connect command. AACP handshake and route verification remain separate outcomes.

Required invariants:

1. At most one unresolved OS connect command per local headset. A new intent never blindly overlaps an older command.
2. `connect()` returning true means accepted, not connected. Success requires fresh profile evidence for the target; during active playback also report route verification separately.
3. Late callbacks must be matched/reconciled against current adapter and attempt state. Old retries cannot issue commands after cancellation or replacement.
4. Reservation bookkeeping does not increment ownership revisions. Ownership/audio-use changes do.
5. While Android reports connecting, observe it rather than repeatedly calling connect. If it later reports disconnected, evaluate a bounded retry immediately; do not depend only on timers that already ran while connecting.
6. A timeout without a definite OS outcome does not prove release. Reconcile before another attempt or before granting a competing reservation.
7. Case-open reconnection never starts playback by itself.

## Scheduling and case evidence

Handle decisions on a serialized worker rather than the UI thread. On receiving a valid proximity/ear event, maintain a bounded execution opportunity for the short active attempt; release it on terminal state or hard timeout. This must be validated under real screen-off/device-idle behavior and must not become continuous background polling.

Separate intent freshness from Bluetooth attempt timeout. Before issuing a command after delayed scheduling, reacquire fresh evidence: earbuds still available/out of the case, current policy, no competing owner, local Bluetooth usable. Renew eligibility only on fresh evidence. Do not extend an old request indefinitely merely because its callback was late.

BLE silence means unknown. A closed case with both buds positively stored cancels passive recovery. Closing an empty case after removing the buds does not. Repeated advertisements and silence/reappearance belong to the same episode until real state transitions or expiry justify a new one.

Initial experiment: at most two actual connect commands per passive episode. Derive attempt and overall deadlines from measured profile timings; the existing 15-second intent can expire before a 21-second platform attempt finishes. A bounded execution timeout still applies, but do not fabricate a failure/release outcome for an unresolved platform command.

## Peer protocol and offline behavior

Use existing session-authenticated snapshots and reservation messages. A requester sends intent ID and expected ownership revisions. A reachable participant replies with either a matching grant or a named reason plus current state. Commit/start acknowledgement precedes the OS command; cancellation and outcome resolve the matching reservation. Duplicate messages are idempotent and stale sessions are rejected.

Refresh stale state once within a bounded coordination phase. A connected but unsynchronized peer remains an uncertainty; silently treating it as offline is unsafe. Tie arbitration and grants to one attempt and use a deterministic installation-ID tie-break when equal-priority intents genuinely compete. Actual local active calls receive explicit protection; merely hearing a ring or seeing playback elsewhere is not blanket authority.

If every configured peer is unreachable, permit cautious passive recovery for the remembered last holder, subject to fresh case/ear evidence and no credible contrary ownership. When peers return, reconcile actual profile state and invalidate old grants before further commands. If contention is observed, stop passive retries rather than fighting.

This is an explicit availability tradeoff: a silent peer could be powered off or could still hold the AirPods with its coordination link down. No app-only protocol can guarantee both offline automatic recovery and zero cross-device contention under that uncertainty. Likewise, independent saved last-owner flags may disagree after a partition. Do not claim globally exclusive ownership from those flags.

## Diagnostics and user messages

Journal intent receipt, scheduling lag, policy revision/effective value, profile evidence, peer grant/denial, command dispatch/acceptance, profile transitions, route verification, retries and final result. Retain at least 48 hours. Add power state and available platform/controller failure details. Reconcile after process restart; persisted in-flight attempts are history, not permission to reconnect.

Each episode gets one outcome: already connected, connected, failed profile connection, unresolved platform timeout, peer busy, policy disabled, stale intent, buds stored, Bluetooth unavailable, or cancelled/replaced. Display moved-away UI only for a verified local departure. Avoid notifying observers about routine peer activity.

## Implementation sequence and acceptance gates

1. Extract a pure coordinator and typed policy/evidence model. Connect it to a fake clock and fake Bluetooth/peer adapters. Keep current connection behavior until adapter integration.
2. Replay the captured traces as integration tests: callback 88 seconds late; accepted -> connecting -> disconnected after 21 seconds; delayed success after 11 seconds; policy observations false then true; profile-connected reports from both devices; unavailable peers.
3. Route case-open, ear-in, playback, call, manual and reconnect callbacks through the coordinator. Remove superseded timer/command paths rather than leave two authorities. Add an architectural check that callers cannot directly initiate A2DP commands outside the adapter.
4. Validate grants with two simulated devices, duplicate/out-of-order messages, stale revisions, simultaneous intents, peer loss/rejoin and process restart. Use the same coordinator and message adapter as production.
5. Build/install on both devices only after automated gates. Physically test repeated cycles in both directions, screen off/device idle, peer Bluetooth off, peer link lost while it still owns audio, immediate case closure after removal, buds stored, failed first connection, and genuine call/media contention.
6. Record latency distributions and failure reasons. Require observed reconnection without playback, no oscillation, and accurate notification behavior. A passing build and pure policy tests are not physical validation.

The physical test run must establish realistic latency targets and timeout limits before treating this as production-ready. No zero-failure guarantee is possible for the Android Bluetooth stack or an unreachable headset.

### Implemented simultaneous passive-request rule (validation in progress)

When synchronized requests for the same headset, membership and revisions cross, the requester with the lexically larger normalized installation ID yields to the smaller authenticated requester only while its own request is PREPARING. It sends its cancellation to all peers and grants the winning request. It never yields an already STARTING or IN_PROGRESS request; an old or session-mismatched request cannot cause a yield. This rule applies only to the passive reservation exchange; call, manual and media priorities still require separate end-to-end validation. There is no global exclusion guarantee when peers cannot communicate.

### Peer-assisted case observation (implementation under validation)

When an online peer's own scanner is unavailable, a different configured phone may see an IRK-verified lid-open or in-ear advertisement for the same saved headset. That observation is a hint, not permission to take audio. An optional, separately identified secure RFCOMM endpoint carries a bounded hint/challenge/confirmation exchange; existing v2 frames and its strict HELLO capabilities are unchanged. A peer without the endpoint receives no extension frame on its v2 link. A bonded connection and a current v2 session are both required, and each side checks the other session again during the exchange.

The endpoint uses Android's authenticated and encrypted RFCOMM APIs: [`listenUsingRfcommWithServiceRecord`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingRfcommWithServiceRecord(java.lang.String,%20java.util.UUID)) and [`createRfcommSocketToServiceRecord`](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createRfcommSocketToServiceRecord(java.util.UUID)). Bonding and configured-peer/session checks remain separate requirements; successful socket creation is not proof that the case is open.

The observer rechecks that the same keyed observation is still available and no more than five seconds old. The receiver permits a challenge only for its configured headset, enabled policy, remembered last holder, and synchronized peers; it rechecks these, local stored evidence, and the local evidence revision after the response and before use. The nonce and observation ID prevent duplicate use within the freshness window. The receiver uses its own clock for round-trip timing; it never compares elapsed clocks across phones. A confirmed report opens a bounded passive episode and must still obtain the existing peer reservation and pass all command-boundary checks. Detected peer loss, local contradictory stored evidence, policy or session changes, and expiration invalidate the evidence. This does not help when the only observing peer is unreachable and does not override an unknown local profile or active call. Endpoint compatibility, radio impact, process lifecycle, and actual headset behavior require physical testing; the peer is trusted to report its own verified BLE observation honestly.

An observer without the headset's IRK cannot forward its RSSI-only limited-mode observation as verified evidence. An unopened or closed-empty case is not inferred from BLE silence; forwarding a new closed-empty observation requires subsequent positive in-ear evidence. A later positively verified stored observation withdraws each recent hint over the same secure extension. The receiver tombstones the observation even if withdrawal arrives before its hint, and invalidates pending or accepted evidence before subsequent dispatch checks. Withdrawal is subject to the peer link and timing: it cannot undo a command already delivered to Android or guarantee notification after a partition. Those races require device validation.

Each active endpoint exchange uses a worker-thread socket deadline and a short partial wake lock with an automatic four-second maximum; it releases the lock when the socket closes. Android documents the automatic expiry of [`WakeLock.acquire(timeout)`](https://developer.android.com/reference/android/os/PowerManager.WakeLock#acquire(long)). Failure to acquire a wake lock does not establish that an exchange completed; suspend and radio behavior still need physical testing.
