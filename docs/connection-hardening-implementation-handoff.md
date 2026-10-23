# Implementation handoff: reliable DIRCON and automatic peer recovery

## Implementation status (2026-10-23)

The automated/code implementation is complete on `codex/connection-hardening`,
based on `437e74fe` (PR #49). It is not production-ready until the physical
acceptance gates below pass. See
`docs/connection-hardening-validation.md` for evidence and pending gates.

The shared-ownership gate and v2 reservation path are wired: the listener and
outbound client exchange HELLO, snapshots and heartbeat PING/PONG; bind
installation/session identity; arbitrate duplicate links; dispatch strict
ACQUIRE_REQUEST/GRANT/DENY/CANCEL/START/ACK frames; and authorize one passive
lid/case/ear acquisition only after a fresh matching all-peer reservation. Lid
close, relevant preference changes, ownership changes, failed cooldown gates and
manual takeover cancel the intent. Peer capability negotiation fails closed for
older peers. Duplicate START is idempotent only while current ownership remains
safe. This is automated policy/protocol evidence, not proof of device-level
recovery or contention behavior.

Checkpoint B is substantially wired. `PeerConnection` owns per-peer sockets,
queues and jobs; `PeerConnectionSupervisor` owns the parent scope, one global
RFCOMM dial gate, injectable socket factory/monotonic clock, per-peer loop
registration and close-before-join operations. Peer removal closes inbound and
outbound links and clears readiness, queues and ownership observations. Permission
loss waits for a foreground/service recheck instead of spinning; listener endpoint
generations retain their own accept socket and stop retrying on permission denial.
Supervisor lifecycle and global dial serialization have JVM tests. Physical
two-peer timing/contention remains open, and listener Android callback races still
need on-device validation.

DIRCON now has a `DirconSessionController` for transport generations, typed
retryable versus terminal failures, a bounded jittered retry sequence, Stop
invalidation and Wi-Fi-available wakeups. `DirconNetworkMonitor` deterministically
selects a suitable local IPv4 Wi-Fi endpoint and watches its Android Network
identity before binding. The broadcaster retains per-client request deadlines,
write watchdogs, ordered subscription acknowledgements and latest-sample
conflation. A registration lease handles Stop-before-NSD-callback races and a
fake-clock-tested token bucket enforces 100 requests/second with burst 200.
Deterministic tests cover stale transport/network callbacks after Stop. Physical
screen-off/network recovery validation remains pending. Sharing state now reports explicit intent, sensor phase,
transport phase and retry time; a pure reducer has tests for independent sensor
and transport recovery. Stale-client invalidation occurs once per stale transition,
and sensor recovery timeout preserves its original cause. NSD registration now has
a deadline and late callbacks are still invalidated by Stop. The wakelock is held
only with live sensor data and an active listener phase, not while waiting for
Wi-Fi. Port-in-use is a terminal, localized error across all supported locales.

Peer heartbeat uses correlated nonces, and v2 READY requires a matching PONG
with an accepted current snapshot. Legacy and v2 blocking RFCOMM dials have
socket-closing deadlines. Listener endpoints track and close accepted sockets on
shutdown; legacy keep-alive work is parented to the listener peer scope. Peer
membership reconciliation closes removed-peer transports. JVM tests cover the
deadline, timeout close, heartbeat, socket/queue teardown and relevant callback
races; physical Bluetooth behavior remains pending.

Checkpoint G now has the independent `DIRCON_ENABLED` flag (debug and
relay-preview on, release off), translated DIRCON copy in all supported locales,
search terms for Wi-Fi receivers, and peer/DIRCON lifecycle events in the
existing redacted diagnostic ring and export. Physical acceptance remains open.

The user-reported Incyclist and Zwift reception applies to the earlier prototype.
No physical two-phone reconnect, audio-contention, receiver-app recovery or
DIRCON fault-injection validation has been performed for this implementation.
Do not mark a checkpoint complete based on code, mocks, build or install alone.

Automated DIRCON client coverage includes the four-client cap, bounded response
overflow, blocked-write deadline, 100 sequential client cleanup cycles,
partial-frame drip-feed timing and token-bucket burst/refill using elapsed clocks.
Latest unit suites passed on 2026-10-23: 304 Debug and 339 relayPreview tests.
All three variant assemblies and lint tasks, localization validation and
`git diff --check` also passed. Device radio, screen-off and receiver recovery
gates remain open; see the validation document.

## 0. Instructions to the implementing agent

Implement this document in order. Treat the specified behavior and invariants as
requirements. Do not replace them with simpler timeouts that authorize takeover,
unbounded retry loops, or optimistic UI flags. Keep each checkpoint reviewable.
Do not mark a physical acceptance test passed from code, mocks, build or install.
If hardware is unavailable, complete the code and automated checks and explicitly
list the physical gate as pending. Do not invent device results.

This document is the implementation specification. The companion
`docs/connection-hardening-plan.md` contains the source review and rationale.
The user explicitly authorized implementation of this handoff and said physical
testing will happen at the end. Complete code and automated validation while
keeping hardware-only gates pending until they are actually run.

### Starting state

- Repository: EveryPods. Correct working directory at handoff time:
  `/Users/nikos/.codex/worktrees/dircon-latest/Everypods`.
- Original plan drafting base: `19b9ffc02e650f0d77dd001a28c234656e53cf44`,
  merged PR #48. The implementation worktree was later synchronized to
  `437e74fe` (PR #49); do not reset it to the drafting base.
- This worktree is detached and contains uncommitted DIRCON prototype changes.
  They are required inputs, not disposable scratch changes.
- `/Users/nikos/Everypods` is an older checkout with unrelated work. Do not copy
  that entire checkout over this one or discard its changes.
- The normal app ID is `io.automated.ventures.everypods`. The user chose it.
- Current test build is `relayPreview`, version `1.0.4-rc19-relay-dircon`.
- User confirmed reception in Incyclist and Zwift. Endurance, network recovery
  and automatic peer recovery have not been verified by that confirmation.
- ANT+ was explicitly dropped. Do not add it.

Before editing: inspect git status, HEAD, applicable AGENTS.md, current tests and
the companion review. If this worktree moved, locate its attached replacement.
Create a `codex/connection-hardening` branch from the actual current worktree if
appropriate; preserve all local prototype files. Do not reset, clean, stash other
work, commit, push, uninstall apps or clear app data without authorization.
Re-read code at each named function; line numbers from the review may move.

### Existing files to inspect

Paths below are relative to `android/app/src/main/java/io/automated/ventures/everypods/`:

| File | Responsibility / edits |
|---|---|
| `services/AirPodsService.kt` | HR sharing lifecycle; `startHeartRateSharing`, `startDirconHeartRateTransport`, `stopHeartRate`, `recoverHeartRate`; lid scheduling, `mayContinueLidAutoconnect`, retries and proactive connect paths; peer lifecycle and ownership hooks. |
| `bluetooth/hrm/HeartRateSession.kt` | Existing sensor state, five-second freshness and 15-second no-data detection. Preserve sensor decoding. |
| `bluetooth/hrm/HeartRateSharingPrefs.kt` | Saved method and memory-only sharing intent; gym stop suppression. |
| `bluetooth/hrm/HeartRateSharingState.kt` | UI/notification sharing status. |
| `bluetooth/hrm/dircon/DirconProtocol.kt` | Existing wire codec; do not change successful wire fixtures casually. |
| `bluetooth/hrm/dircon/HeartRateBroadcaster.kt` | Split socket transport from recoverable session management. |
| `utils/CrossDevice.kt` | Legacy endpoint, listeners, ownership notifications, legacy dispatch. |
| `utils/CrossDeviceClient.kt` | Replace independent retry/delay loops with owned connections and supervisor. |
| `utils/CrossDeviceReconnectPolicy.kt` | Replace indefinite local-headset suppression only after safety gate exists. |
| `utils/AudioLeasePrefs.kt` | Historical ownership hint, not authorization. |
| `utils/LidAutoconnectPolicy.kt` | Central passive connection decision; preserve contention fallback. |
| `presentation/viewmodel/AirPodsViewModel.kt` | Per-peer status and retry; peer removal must close inbound and outbound links. |
| `presentation/screens/HeartRateScreen.kt` | Saved transport, permissions, lifecycle status and errors. |
| `presentation/components/HeartRateCaseControl.kt` | Same status and Start/Stop behavior on Home. |

Also inspect `android/app/build.gradle.kts`, `AndroidManifest.xml`, all production
variants, `src/relayPreview`, `src/test`, `src/testRelayPreview`, resources, search
catalog and `android/scripts/validate_localization.py`. Windows source is not
present in this checkout. Do not claim that legacy Windows compatibility was
verified without its client or fixtures.

## 1. Non-negotiable invariants

I1. Peer transport reconnection never calls `takeOver`, `connectAudio`,
`disconnectForCD` or any headset acquisition command.

I2. A lost peer link means UNKNOWN ownership, not RELEASED. Socket-connected is
not the same as protocol-ready or ownership-synchronized.

I3. Passive lid/proximity/startup/retry actions cannot acquire AirPods while a
relevant configured peer's ownership is unknown. No timeout relaxes this rule.

I4. Explicit user takeover, existing media-start and call policies remain distinct
from passive connection. Preserve existing user settings and semantics for them;
do not silently turn all automatic actions into user-authorized takeover.

I5. Stop/disable/remove invalidate a generation before canceling its children.
Callbacks from old generations cannot mutate state, reopen sockets or publish.

I6. Only real, fresh samples from the currently owned target AirPods are sent.
No fake zero BPM, invented RR intervals, cached replay or recovery-driven takeover.

I7. One canonical ready connection per peer and one writer per socket. At most
one outbound Bluetooth connect attempt across the supervisor at a time.

I8. UI, notification and decisions use the same immutable state snapshots.
Raw socket flags and a global `anyPeerConnected` flag cannot authorize actions.

I9. Use elapsed monotonic time for deadlines and freshness. Wall time is for logs
only. Do not compare elapsed timestamps sent from different devices.

I10. No broadcasting after app process restart/reboot solely because a saved
method exists. Preserve existing explicitly enabled gym behavior and manual Stop
suppression; do not broaden automatic session creation.

## 2. Checkpoint A — stop passive stealing

Implement this first, before changing retry frequency.

### New pure policy and observation types

Add `utils/PeerOwnershipState.kt` and expand `LidAutoconnectPolicy.kt`.
Suggested types (equivalent names are acceptable, equivalent semantics required):

```kotlin
enum class PeerOwnership { UNKNOWN, HELD, RELEASED }
enum class PeerAudioUse { UNKNOWN, IDLE, MEDIA, CALL }
data class PeerObservation(
    val peerId: String,
    val headsetId: String,
    val sessionId: String,
    val revision: Long,
    val receivedAtElapsedMs: Long,
    val ownership: PeerOwnership,
    val audioUse: PeerAudioUse,
)
sealed interface PassiveDecision {
    data object AlreadyLocal : PassiveDecision
    data object AllowedStandalone : PassiveDecision
    data object NeedsReservation : PassiveDecision
    data class Wait(val reason: Reason) : PassiveDecision
}
```

- Keep configured-peer membership separate from observed peers. Missing entries
  are UNKNOWN. Initially all configured sharing peers are relevant; do not ignore
  a peer just because it is offline or its headset identity is unknown.
- On link loss, Bluetooth-off, peer process/session change, reset or observation
  expiry, invalidate its current observation. Retain history for explanation,
  never as an authority. Restarted apps begin with UNKNOWN observations.
- In shared mode, local persisted lease is not enough. First checkpoint safely
  blocks passive acquisition while later protocol work is incomplete. Standalone
  behavior and explicit takeover keep working.
- Route initial lid action, delayed lid retries, flux recheck, startup saved-MAC
  and other passive AACP/audio connection paths through the same policy. Audit all
  calls to `connectAudio`, proactive `connectToSocket` and `mayProactivelyConnect`.
- Cancel pending passive work when lid closes, setting changes, membership changes,
  a peer becomes HELD/UNKNOWN or local user action supersedes the event.
- Preserve the current post-contention cooldown, but use elapsed time.
- Do not send legacy REQUEST_HANDOVER to obtain permission for passive acquisition.

Tests: historical local lease + missing peer; peer was released then link drops;
process restart; one of three peers missing; remote media/call; standalone;
manual takeover remains possible; every delayed retry rechecks the policy.
Checkpoint gate: no passive headset command can run with unknown shared ownership.

## 3. Checkpoint B — deterministic peer transport lifecycle

Add a `utils/peer/` package with `PeerConnectionSupervisor`, `PeerConnection`,
`PeerConnectionState`, `PeerRetryPolicy`, and injectable clock/socket factory.
Keep CrossDevice's external facade initially to minimize unrelated call-site edits.

### Ownership and scheduling

- Supervisor owns one parent SupervisorJob. Per-peer children own connect,
  handshake, reader, writer and heartbeat tasks; avoid detached CoroutineScopes.
- State: DISABLED, WAITING_FOR_BLUETOOTH, BACKOFF, CONNECTING, SYNCING, READY.
  Errors have typed causes separate from state. Permission loss waits for user
  correction. Bluetooth-off waits for adapter-on, not repeated connect failures.
- Store the pending socket before calling blocking `connect()`. A timeout job
  closes that exact socket; cancellation closes it in finally. Merely wrapping a
  blocking call in `withTimeout` is insufficient. Never block the main dispatcher.
- Each attempt and listener has a generation. Hold a local listener reference in
  its accept loop. Do not let an old loop close/restart a new listener through a
  global mutable `serverSocket` reference.
- Every send/read/heartbeat failure funnels through one idempotent link-close
  operation, clearing READY, invalidating observations and scheduling recovery.
- Manual Retry wakes the same scheduler, cannot spawn a second loop or repeatedly
  bypass the global single-attempt limit. Remove artificial delay-job replacement.
- Peer removal closes all inbound/outbound/pending connections for that peer and
  removes queued sends. Disabling transport cancels and joins listener/children.

### Initial tuning constants — provisional until physical gate

| Setting | Initial value |
|---|---|
| Connect deadline | 8 seconds, enforced by socket close |
| Protocol handshake deadline | 5 seconds |
| Retry backoff without local audio | 2, 4, 8, 15 seconds, capped at 15 |
| Jitter | +/-20%, injected deterministically in tests |
| Retry interval during media/call | At least 15 seconds; one attempt globally |
| Ready heartbeat interval | 10 seconds |
| Response deadline | 10 seconds after an outstanding ping |
| Consecutive missed responses | 2, then close |
| Snapshot freshness | 35 seconds; acquisition still requires a fresh round-trip |
| Maximum queued control frames | 64; overflow closes unhealthy link |

These are not latency promises. If real testing shows audio disruption, reduce
radio pressure before release. Keep an experimental rollout flag until measured.
Do not fall back to indefinite suppression while reporting recovery as fixed.

Short connection duration does not prove an absent server. Retry ordinary errors
with bounded backoff. ACL events and app foreground may wake eligible backoff;
range return itself need not produce an ACL event. Keep periodic recovery attempts.
Do not call Bluetooth discovery or cancel a user's unrelated discovery globally
as a routine retry side effect.

Tests: stop during blocked connect; timeout closes pending socket; late success
ignored; repeated Retry; failed writer wakes reader cleanup; listener replacement;
peer remove during connect; Bluetooth toggle; jitter bounds; two peers never dial
simultaneously; no lifecycle path calls headset takeover APIs.

## 4. Checkpoint C — safe protocol migration and synchronization

### Separate v2 endpoint

Keep legacy UUID `1abbb9a4-10e4-4000-a75c-8953c5471342` and its existing messages.
Use UUID `97ce0b4d-8e2f-4a6d-b6cd-d69d362e75ac` for v2 and document it in
`docs/peer-protocol-v2.md`. Never generate it at runtime. Both updated Android
peers listen on it. Do not send a new framing prefix onto a legacy connection.

First contact may attempt v2 then fall back to legacy, within the same retry
budget. Cache a successfully observed capability; do not infer permanent legacy
support from a timeout. Probe again on explicit Retry/app start or a bounded
ten-minute interval while disconnected, never by tearing down a healthy link.
Legacy peers remain usable for existing explicit handover. They cannot issue v2
passive reservations, so shared passive auto-connect stays conservative for them.

### Framing contract

v2 frame: four-byte magic ASCII `EPH2`, one-byte version `2`, one-byte type,
four-byte unsigned big-endian payload length, followed by UTF-8 JSON payload.
Maximum payload 4096 bytes; retain at most header + max payload in decoder buffer.
Unknown types/versions, invalid lengths/JSON/required fields close only that peer.
Use existing JSON facilities; avoid a new large dependency. Reject duplicate or
inconsistent identity fields; bounds-check numeric ranges. Never recover framing
by scanning arbitrary bytes for a new magic inside malformed payloads.

Types (assign stable numbers 1 through 9 in this order): HELLO, SNAPSHOT, PING,
PONG, ACQUIRE_REQUEST, ACQUIRE_GRANT, ACQUIRE_DENY, ACQUIRE_CANCEL, RELAY_DATA.
Types 10 and 11 are ACQUIRE_START and ACQUIRE_START_ACK (see checkpoint D).
Define exact schemas and golden fixtures before wiring side effects.

- HELLO: installation UUID, new process-session UUID, protocol capabilities,
  initiator UUID, random connection UUID. Bind asserted identity to the configured
  Bluetooth remote address; do not let JSON impersonate another configured peer.
- SNAPSHOT: sender session, monotonically increasing revision, target headset ID,
  ownership, local audio use, last confirmed owner ID/revision if known, and any
  acquisition in progress. Source ownership from actual A2DP/AACP state, not the
  persisted lease. MEDIA/CALL/IDLE must describe this headset's local route where
  observable; otherwise use UNKNOWN. Do not call unavailable playback data IDLE.
- PING/PONG: nonce. Matching PONG carries the current snapshot, so heartbeat
  freshness represents a current status reply rather than successful writing.
- ACQUIRE messages: request UUID, headset ID, requester session, membership digest,
  and relevant snapshot revisions. Grants refer to a specific request, never a
  global boolean. Denial includes a bounded enum reason, not an exception string.
- RELAY_DATA: declared subtype and encoded bytes with decoded-size limit; keep
  battery/ANC/ear-state handling behind the existing trusted dispatcher.

All reader chunks go through a persistent per-connection incremental decoder;
one read may contain half a frame or multiple frames. All writers, including
heartbeats and relayed data, use the same queue. Do not use the existing heuristic
that removes identical packet halves on v2.

### Bootstrap, duplicate arbitration, legacy compatibility

- Store a stable installation UUID in app-private preferences; do not regenerate
  it per connection. Session UUID changes each process lifetime. Identity changes
  invalidate old observations and grants, and trigger synchronization.
- On first contact either Android device may dial after randomized delay. When
  two v2 links exist, both endpoints select the same smallest lexicographic tuple
  `(initiatorInstallationId, connectionId)` from exchanged HELLO identities. Close
  the loser without clearing the winner's observation. After identities are known,
  the lower installation ID is preferred dialer; permit budgeted fallback if it
  is not recovering. Bluetooth names have no role in the new election.
- Do not close a healthy legacy connection until the replacement v2 connection
  completes HELLO and current snapshot synchronization. A stale loser callback
  must not remove the winning link or its ownership state.
- Legacy four-byte commands need a buffered decoder for fragmented/joined control
  frames. For relayed variable-length legacy data, derive lengths from actual
  message parsers/captured fixtures. Do not guess a boundary from read() or idle
  time. If an unknown legacy payload cannot be framed, invalidate synchronization
  and close/reconnect rather than misinterpret payload bytes as takeover commands.
- Preserve allowlisting of configured remote addresses. Do not accept unconfigured
  inbound peers. Do not silently rewrite bonding/security behavior in this work.
- Windows source is absent: preserve its endpoint; add recorded legacy fixtures
  and a physical interoperability gate. Do not advertise full legacy framing
  support until the relevant actual packet formats have been verified.

Tests: every byte split of each v2 type; concatenated messages; oversized/invalid
input; reordered session/revision; spoofed identity; simultaneous dial; same names;
missing names; new installation identity; healthy legacy-to-v2 upgrade; legacy
control golden fixtures; no raw read passed directly to a side-effect dispatcher.

## 5. Checkpoint D — passive acquisition and automatic recovery

Add pure `PassiveAcquisitionCoordinator` and `PeerOwnershipStore` with an injected
clock. Keep acquisition separate from the transport supervisor.

1. A valid lid-open event creates an intent expiring after 10 seconds. Lid close,
   setting changes or explicit local action cancel it. Do not revive expired
   intents when a phone returns minutes later.
2. Require local headset unowned, historical local last-owner eligibility, all
   configured relevant peers READY on v2, and no conflicting last-owner evidence.
   Unknown last-owner history after migration requires manual acquisition once;
   confirmed ownership then synchronizes history. Do not bootstrap authority from
   the old local boolean alone.
3. Freeze configured membership for this request and require matching membership
   knowledge, including the requester, at participants. Inconsistent configuration
   denies passive acquisition; do not silently omit a peer from the quorum.
4. Send ACQUIRE_REQUEST to every other participant. Each participant grants only
   if it currently does not hold that headset, has no contradictory ownership
   observation, and has no grant outstanding to a different requester. Reserve
   atomically before enqueueing GRANT. Requests do not release any AirPods.
5. Preparation grant lifetime is five seconds on the grantor's clock. Requester
   must receive every grant within two seconds of its local request send time,
   then send ACQUIRE_START to every participant. Each validates its unexpired
   matching grant and atomically changes it to IN_PROGRESS before acknowledging.
   Requester may issue a headset command only after every START_ACK arrives within
   three seconds of the original request send time. These conservative bounds
   avoid comparing clocks. A stale/late/mismatched reply is ignored.
6. Immediately before the headset command, recheck lid, setting, membership,
   generations, target, remote ownership and deadline. Losing any required peer
   or receiving an ownership update cancels the reservation. Snapshot HELD always
   overrides an earlier grant.
7. Each participant grants at most one requester. Conflicting simultaneous
   attempts deny, cancel and use jitter before a new round while the lid intent
   remains valid. Never issue partial-quorum connects. Sequential retries need
   new grants; do not reuse an expired token for existing delayed A2DP retries.
8. Confirm ownership only from actual local connection/ownership signals, then
   publish a new snapshot. An IN_PROGRESS record does not expire into permission
   for a rival: Bluetooth acquisition is asynchronous and could finish after a
   timer. Resolve it only on confirmed acquisition, or a cancellation/failure
   proving no headset attempt remains pending. On link loss or process/session
   change, mark it unresolved/UNKNOWN and block new passive acquisition until
   synchronization resolves it. The same rule applies if only some START_ACKs
   arrived: send CANCEL, and participants hold conservatively until it arrives.
   A bounded acquisition watchdog can report failure and cancel further work,
   but cannot declare the headset free while an OS connection remains in flight.
   Preparation expiry releases only an unstarted reservation and never changes
   unknown observed ownership to RELEASED.
9. An explicit manual takeover invalidates local passive intents/reservations,
   follows existing takeover settings, then publishes actual resulting ownership.
   HR recovery and peer reconnection cannot invoke this path.

Scope of guarantee: cooperating configured peers following this protocol. An OS,
unmanaged device or explicit user takeover can still change the AirPods connection.
Retain contention detection as a fallback; don't claim distributed grants prevent
external Bluetooth actors from changing ownership.

UI consumes supervisor state: Reconnecting automatically, Waiting for Bluetooth,
Synchronizing, Connected. Show unknown ownership honestly. Keep manual Retry as
an escape hatch without requiring it for routine range recovery.

Tests: two simultaneous requesters; one disconnected of three; one legacy peer;
membership mismatch; grant expiry; late grant after Stop; link loss just before
connect; remote ownership changes during grant; duplicate request idempotency;
same request with changed payload rejected; confirmed connect updates history;
partial START_ACK; OS connection completing after preparation deadline; unresolved
in-flight reservation never permits a rival; manual takeover and old user
preferences remain functional.

## 6. Checkpoint E — recoverable DIRCON sessions

Add `bluetooth/hrm/dircon/DirconSessionController.kt` and
`DirconNetworkMonitor.kt`; refactor the current broadcaster into one network
generation's listener/advertisement/client transport. Keep `DirconProtocol` wire
behavior unchanged unless receiver evidence requires a documented fix.

### State contract

Keep user intent separate from actual resources. Extend sharing state with typed
transport and sensor phases, rather than adding ambiguous overlapping booleans:

- Intent: STOPPED or REQUESTED (memory-only; method and target headset).
- Sensor: WAITING, LIVE, STALE, RECOVERING, NOT_OWNER, FAILED.
- Network/transport: WAITING_NETWORK, STARTING, DISCOVERABLE, RETRYING,
  ACTION_REQUIRED, STOPPED.
- Receivers: connected count, subscribed count, current-generation sent count.
- Typed cause and next retry time; internal diagnostics separate from UI copy.

Adapt existing consumers during migration. LIVE sharing requires a fresh real
sample plus an active subscribed receiver; advertised alone means discoverable.
Keep the existing TICKR and BLE paths and gym behavior working throughout.

### Events and side effects

All commands/callbacks enter one serialized controller (actor or one dispatcher).
Do not run blocking socket calls on that dispatcher. Each child emits events with
session ID and network generation, discarded if obsolete.

| Event | Exact action |
|---|---|
| Start with valid consent | Set requested intent once; check permission, target and sensor eligibility; observe suitable Wi-Fi. |
| No Wi-Fi | WAITING_NETWORK, retained intent, no listener, no wake-lock polling loop. |
| Wi-Fi available | Select network/address, create listener, then register NSD for that same network; revalidate before publishing readiness. |
| Network lost/IP changed | Invalidate generation, close listener/clients, unregister old NSD; wait/rebuild on suitable network. Keep user intent. |
| NSD failure | Preserve accurate listening-vs-discoverable state, retry registration at 1/2/4/8/30 seconds with jitter and bounded burst. After six failures wait 60 seconds or a network/user event; never spin. |
| Permission denied/revoked | ACTION_REQUIRED; release network resources; resume only after a foreground permission check confirms access. |
| Address in use | Typed actionable error; keep port 36866 initially. Do not quietly choose another port until receiver tests prove it. |
| Client error/disconnect | Close that client only; keep healthy clients/server. |
| Sensor becomes stale | Clear displayed BPM; discard all unsent measurement data; invalidate affected clients once per stale epoch. |
| Same target resumes | Accept only fresh post-recovery samples; restore receiver availability; never replay last sample. |
| Ownership moves/device changes | Terminal session stop with specific reason, no attempt to reclaim AirPods. |
| User Stop/method change | Invalidate session first, cancel every retry/resource, then publish stopped state. |

While stale, discovery can remain active, but incoming connections must not enter
a subscribe/disconnect loop: allow discovery/control requests, leave measurement
unavailable, and only activate publication once fresh data returns. No artificial
zero sample. A freshly connected client during a stale epoch is not repeatedly
closed by the watchdog. Existing clients are invalidated once at the transition.
Confirm receiver behavior in both apps; if either requires a different signal,
document actual evidence before changing this contract.

Sensor retry budget: retain existing 2/4/8-second cadence for up to 60 seconds;
then stop polling and show Waiting for AirPods/sensor. Wake on a relevant local
connection/ownership event, explicit Retry, or at most one 30-second attempt while
the same valid local connection persists. A start-command ACK is not a fresh BPM
sample and must not reset the failure budget. Reset recovery only after fresh data.
Preserve actual error cause, not a generic SEND_FAILED on deadline expiry.

Network scope for first release: existing Wi-Fi IPv4 LAN behavior, including
Wi-Fi without internet. Preserve stable identity/name rules. Choose deterministically
and retain a suitable network until it becomes invalid. Explicitly report unsupported
address configurations; defer IPv6-only/hotspot support rather than silently bind
to every interface. A returning network has a new Android Network identity.

### Resource rules

- No full-session wake lock while waiting indefinitely for Wi-Fi or sensor.
  Use narrowly scoped lock ownership for active streaming/connection work and
  verify screen-off behavior physically. Stop releases every held lock.
- Own pending NSD registrations explicitly. Stop-before-registration-completes
  still unregisters on the late callback; obsolete callbacks never set ready.
- Idempotent teardown with tracked cleanup failures, no blind duplicate register.
- Process death closes the session. Saved transport remains DIRCON; reopening
  settings must not silently start broadcasting.
- Service, Home, notification and settings observe the same session state.

Tests: fake network callbacks + NSD callbacks in every Stop race; Wi-Fi unavailable
then available; address replacement; late old callbacks; permission failure;
listener failure; old client close cannot decrement new counts; stale once per
epoch; ACK without samples; long outage; new target; handover; method change;
manual gym suppression; no active session after process recreation.

## 7. Checkpoint F — bounded DIRCON client I/O

- Maintain four-client limit and 4096-byte payload bound. Accept/reject atomically.
- Initial valid request deadline 15 seconds. An incomplete frame must finish
  within five seconds from its first byte even after subscription. Idle subscribed
  clients are allowed; a partial-frame deadline is different from idle silence.
- A blocked write gets five seconds, enforced by closing that socket from a
  separate watchdog or nonblocking I/O. Java socket read timeout does not bound
  writes. Ensure writer cancellation actually releases the blocked resource.
- One reliable bounded response queue and one latest-measurement slot per client.
  Serialize enqueue, subscription and generation changes. Response overflow closes
  that client; replacing an unsent sample is allowed, replaying old samples is not.
- On unsubscribe, clear its pending measurement and ensure no newly scheduled
  notification follows the unsubscribe acknowledgement. On subscribe, queue ACK
  before activation. Never send samples older than five seconds at send time.
- Request budget: token bucket 100 requests/second, burst 200, per client. Close
  the offender with an internal diagnostic reason. Verify actual app traffic is
  comfortably below the cap before production; don't silently drop commands.
- Clean up client jobs exactly once; counts come from the current client registry.
  Don't count accepted samples as successful socket writes or successful writes
  as proof that the receiver displayed them.

Tests: two healthy and one blocked receiver; abandoned subscribers freed by write
deadline when data arrives; partial frame drip-feed; full slots; queue overflow;
unsubscribe/publish ordering; simultaneous close/Stop; malformed peer isolation;
reconnect receives no cached sample; resource counts stable over 100 cycles.

## 8. Checkpoint G — production integration and rollout

- Replace `DIRCON_TEST` gating with an independent DIRCON rollout flag initially
  enabled in debug/test variants, separate from `AUDIO_RELAY_PREVIEW`. Only enable
  normal release after physical gates. Do not accidentally ship relay preview.
- Preserve saved `DIRCON` preference and stable advertised identity; audit fallback
  behavior if a variant does not support it. Keep default selected method intact.
- Move test copy into localized production resources for all supported locales.
  Label it Wi-Fi heart-rate sharing; put protocol name in details if useful.
- Add clear Wi-Fi/permission/port errors and pending recovery status. Details may
  show receiver count/address and last update age, not raw exception text.
- Home and notification Stop work from every state, including paused/error.
  Waiting does not render a stale BPM or a streaming animation.
- Include the control and relevant errors/settings in the existing search index.
- Explain that sharing is local-network broadcast while enabled; do not imply
  receiver authentication or privacy on an untrusted LAN. No HR history logging.
- Bounded diagnostic ring buffer: transition, reason, generation, retry delay,
  ages, resource counts and passive decision. Redact identifiers. No frequent raw
  payload logging in release. Add a way to include it in existing diagnostic export.

## 9. Validation commands and evidence

Run from the worktree's `android/` directory; inspect task availability first if
build variants changed. Do not rerun broad suites repeatedly without new changes.

```sh
./gradlew :app:testDebugUnitTest :app:testRelayPreviewUnitTest --console=plain
./gradlew :app:assembleDebug :app:assembleRelayPreview :app:assembleRelease --console=plain
./gradlew :app:lintDebug :app:lintRelayPreview :app:lintRelease --console=plain
python3 scripts/validate_localization.py
git diff --check
```

Use existing repository test infrastructure, fake clocks and fake socket/NSD
adapters for policies/lifecycle. Keep real loopback tests for DIRCON I/O. Verify
normal release compilation/minification, not only the preview variant. Existing
lint baselines must not be expanded merely to hide new issues.

Create `docs/connection-hardening-validation.md` with columns: scenario, revision,
devices/app versions, expected result, actual result, evidence, status. Separate
automated, user-reported, agent-observed and pending physical evidence.

### Physical gates — cannot be waived by a green build

Use the normal app ID. Rediscover ADB serials just before install; verify phone
models. Do not uninstall/clear data to resolve signing failures. Do not change user
tracker settings or start fake training data. Ask only for physical actions needed.

Peer matrix (Pixel and Xiaomi, both directions, at least 20 return-to-range cycles):
- Holder playing; non-holder leaves/returns. Repeat with holder leaving/returning.
- Screens off; app background; idle AirPods; active call; each phone as dialer.
- Open case while peer is disconnected: no unsolicited audio grab or interruption.
- Bluetooth toggle; app process recreation; same device names; three configured
  peers with one offline; old Windows client; explicit takeover; peer removal.
- Provisional target: READY within 30 seconds of sustained reachability while both
  services are running. Report p50, p95, maximum and failed cycles. Zero unwanted
  takeovers and no audible retry-induced disruption are release requirements.

DIRCON matrix in both Incyclist and Zwift, with real Assioma equipment when needed:
- Fresh pairing; receiver restart; Wi-Fi off/on; AP/IP change; offline LAN;
  screen off; sensor gap; local AirPods recovery; handover away; permission revoke;
  Stop during recovery; two-hour session; two receivers plus one slow receiver.
- Provisional target: discoverable within 10 seconds of suitable Wi-Fi returning.
  Measure app resubscription separately: EveryPods cannot force a receiver app to
  reconnect. If manual re-pairing is necessary in an app, document it truthfully.
- No stale/synthetic BPM, no post-Stop advertisement or data, no accumulating
  sockets/jobs/listeners/locks, and no unwanted headset takeover.

If OEM power management prevents the target, collect evidence and surface a
specific actionable restriction. Do not silently disable OS settings or promise
automatic recovery from force-stop; user force-stop requires reopening the app.

## 10. Completion checklist and final report

- [x] Checkpoint A: all audited passive entry points use the shared ownership gate.
- [x] B: bounded/cancellable lifecycle, one global outbound dial, one writer per socket and explicit socket teardown.
- [x] C: v2 framing/identity/synchronization and conservative legacy compatibility.
- [x] D: grants and automatic recovery cannot authorize from stale ownership observations.
- [x] E: durable DIRCON session, independent sensor/network recovery, reliable Stop and late NSD cleanup.
- [x] F: deadlines/backpressure/client isolation covered by deterministic tests and loopback faults.
- [x] G: production UI/localization/search/build flags and redacted diagnostics.
- [x] Automated commands pass; existing transport tests pass.
- [x] Physical gates are explicitly pending in `docs/connection-hardening-validation.md`.

Final report must list: changes by checkpoint; exact branch/commit if committed;
builds/tests run and outcomes; installed package/version if installed; physical
observations versus pending gates; remaining limitations; unrelated working-tree
changes preserved. Do not claim production-ready while physical gates are pending.

Do not expand this task into ANT+, new cloud services, cross-platform rewrites,
new tracker integrations or a replacement AirPods sensor protocol. Optional BLE
presence hints and Wi-Fi peer fallback are follow-up work only if measured RFCOMM
recovery cannot meet audio-preservation targets; they are not prerequisites for
the first implementation attempt.
