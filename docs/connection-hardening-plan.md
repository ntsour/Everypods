# DIRCON and Bluetooth peer reliability review

Date: 2026-10-23. Reviewed base: `19b9ffc` (merged PR #48), plus the local
DIRCON prototype in this worktree. This is a design/review, not an implementation.
The user reports successful DIRCON reception in Incyclist and Zwift. These are
functional compatibility results, not yet endurance or recovery results.

## Required behavior

- An explicitly started heart-rate sharing session survives transient sensor,
  receiver and Wi-Fi failures, with truthful status and no stale BPM replay.
- Configured Android peers reconnect automatically when reachable, including
  while one phone is playing through AirPods. Retry remains a troubleshooting
  action, not a routine requirement.
- Losing peer visibility never authorizes passive AirPods acquisition.
- Lid-open auto-connect never interrupts another device's playback or call.
- Explicit user takeover remains a separate action from passive lid-open
  connection; recovery of coordination must never initiate audio takeover.
- Stop, disable, peer removal and ownership changes cancel the relevant pending
  work. Late callbacks cannot revive it.

## Findings in the current code

### Peer handover: release blockers

1. **Unknown ownership can permit a passive grab.**
   `AirPodsService.mayContinueLidAutoconnect` checks the holders set but not fresh
   per-peer status. `AudioLeasePrefs.mayLidAutoGrab` accepts the persisted local
   lease. The holders set survives a socket drop, which is good, but cannot
   represent an ownership change missed while offline, and is empty after process
   restart. The local lease is explicitly local-only. This permits stale local
   ownership to win when remote ownership is unknown. It matches the reported
   failure path; it is not a live reproduction of that particular incident.
2. **Automatic reconnect can be deferred indefinitely.**
   `CrossDeviceReconnectPolicy` refuses all passive attempts while local AirPods
   are connected, including idle connections. The elected client can therefore
   remain disconnected throughout a listening session. Equal or missing Bluetooth
   names elect both phones server-only. Name changes/cached names are also a weak
   basis for durable roles.
3. **Retry scheduling is not one coherent state machine.**
   `CrossDeviceClient` infers no RFCOMM server from any failure under two seconds,
   applying a five-minute delay to potentially transient failures. Its inbound
   connection delay is not cancellable by `retryNow`. `pauseLink` installs a new
   delay job, but the connection loop is not awaiting that replacement job.
4. **Dead sockets can remain reported as connected.**
   Both sides send 20-second pings without a response deadline. A failed ping
   stops the ping loop but does not close the reader's socket. Ordinary send
   failures similarly do not consistently invalidate the entire link.
5. **Socket lifetime has cancellation races.**
   The outbound socket is only stored after blocking `connect()` returns. Stop
   cannot close a connecting socket; a failed attempt can leak that local socket.
   Server restarts use shared mutable socket fields and independent coroutines;
   an old accept loop can race a replacement listener. There is no generation
   identity on peer callbacks.
6. **RFCOMM stream framing is missing.**
   Each read is passed directly to `processPacket`, which compares whole byte
   arrays to four-byte commands. Split commands can disappear; joined commands
   can be ignored. Relayed variable-length data needs explicit framing too.
   Multiple writers also lack a shared outbound queue.
7. **Playback status is incomplete.**
   Android ownership announcements do not carry media/call state. Windows audio
   state is one global boolean rather than per-peer information with freshness.
   Legacy courtesy requests always receive GRANT; they are not a passive safety
   mechanism. Preserve explicit takeover semantics while adding a distinct
   passive acquisition protocol.

### DIRCON: release blockers and hardening

1. **Wi-Fi loss is terminal.** `HeartRateBroadcaster.onLost` and address changes
   fail the transport; the service tears down sharing. There is no listener/NSD
   recreation when the network returns. Initial absence of Wi-Fi is also terminal.
2. **Sensor gaps repeatedly drop clients.** At five seconds without samples, the
   service calls `clearReading` every 500 ms while stale. New receivers can connect
   and be dropped repeatedly. Existing sensor recovery uses a 60-second window,
   then reports SEND_FAILED, losing the actual reason.
3. **A slow client can retain resources indefinitely.** Reads have a timeout,
   but subscribed clients ignore it even with incomplete requests. Blocking
   output writes have no deadline. Four abandoned subscribers can occupy every
   slot. The bounded queue limits queued memory but does not bound blocked I/O.
4. **Concurrent callbacks can give inconsistent status.** Client close/counts,
   NSD callbacks, failure and Stop update shared state from multiple coroutines.
   Some lifecycle guards already exist, but all transitions need session and
   network generation checks. Subscription changes and measurement enqueueing
   also need one ordering point.
5. **Network selection is too narrow.** It picks the first Wi-Fi network with a
   private IPv4 address. Network selection, address changes, NSD failure and port
   conflicts need explicit policy and error categories. IPv6-only networks and
   phone hotspots must either be supported and tested or clearly reported as
   unsupported; do not assume they behave like the tested Wi-Fi setup.
6. **Shipping is still test-only.** DIRCON is gated by DIRCON_TEST in the relay
   preview variant and has untranslated diagnostic strings. Production needs
   normal build integration, localization, search, Home control, notifications,
   and privacy copy that states nearby devices on the local network can receive
   the broadcast while sharing is enabled.

Existing strengths: bounded DIRCON payloads/queues/client count, independent
malformed-client isolation, stable advertised identity, ordered subscription ACK
before activation, no cached BPM replay, explicit Stop, and sensor recovery that
does not take the AirPods from another owner. Keep these properties.

## Implementation sequence

### 1. Prevent passive audio theft first

Introduce per-headset, per-peer state: UNKNOWN, HELD, RELEASED, with monotonic
freshness, peer session identity and revision. A socket connection alone is not
a synchronized peer. Persisted ownership is a hint, never permission to connect.

For configured sharing peers, a lid-open event first schedules coordination.
Passive acquisition requires fresh status from all relevant configured owners,
no remote holder, and agreement on the last owner. Unknown, missing responses or
conflicting ownership means wait without sending A2DP connect commands. A timeout
must never turn UNKNOWN into RELEASED. Once synchronization succeeds, re-evaluate
the still-open lid and user setting; do not replay an expired lid event.

Conservative trade-off: if a configured peer is switched off or unavailable,
passive auto-connect may wait indefinitely. Manual Connect/Take over remains
available. That is necessary to guarantee no stealing during a partition without
an independent authoritative ownership signal. Unshared standalone behavior
remains available; don't silently remove unreachable configured peers.

Use a short, expiring acquisition reservation among synchronized peers to prevent
two phones simultaneously passing the same free-status check. Include request ID,
headset ID and session/revision, and recheck immediately before every A2DP attempt.
Connection success confirms ownership; a request alone does not establish it.
Retain the contention cooldown as a final fallback, not the primary protection.

### 2. Make peer transport recover reliably

- One lifecycle-owned supervisor and connection state machine per peer:
  DISABLED / WAITING_FOR_BLUETOOTH / BACKOFF / CONNECTING / SYNCING / READY.
- One owned listener; one canonical connection per peer. Close pending connect
  sockets on timeout/cancellation. Check generation before accepting any result.
- Stable installation IDs and negotiated roles after initial contact, with
  randomized bounded bootstrap and duplicate-connection arbitration. Support
  identical names and reinstall/identity reset. Windows remains supported as
  an initiating peer; do not repeatedly dial a peer known to be client-only.
- One bounded writer and incremental decoder per link. Introduce versioned,
  length-delimited messages and capability negotiation; audit Android/Windows
  legacy decoders before rollout. Old peers get a conservative legacy mode,
  never assumed support for new ownership grants or playback status.
- Heartbeats with response deadlines; close and invalidate links after missed
  responses. Successful writes alone never prove liveness. Publish READY only
  after a full current ownership/playback snapshot has arrived.
- Backoff with jitter and a cap; wake on applicable Bluetooth/service events and
  observed peer presence. Returning into range does not necessarily emit an ACL
  event, so periodic bounded attempts remain necessary.
- Replace the blanket local-headset ban with a playback-aware retry budget.
  Start with one outbound attempt at a time across peers, finite connect timeout,
  and slower retries during audio/calls. Measure radio contention on Pixel and
  Xiaomi before choosing final timings; don't blindly restore rapid paging.
- Evaluate low-duty BLE peer-presence hints if bounded RFCOMM retries still
  disturb audio. Presence is only a reason to attempt coordination, never proof
  of ownership. Existing authenticated Wi-Fi coordination may be a fallback only
  for already authorized peers, not a new implicit trust path.
- On reconnect, synchronize ownership, playback/call status and headset identity
  before considering any queued automatic action. Recovery itself sends no
  disconnect/takeover command to the headset.

### 3. Give DIRCON a durable active-session lifecycle

Separate user session intent, sensor state, network/advertisement state and
receiver state. Use one serialized session controller with generation tokens.
Stop cancels all children and releases sockets, callbacks, NSD registration and
wake locks. No automatic broadcast after process restart/reboot in the initial
production version; restore the saved method, not a live session.

| Event | Planned behavior |
|---|---|
| Receiver disconnects | Close only that client; keep discovery and server ready. |
| Wi-Fi disappears or IP changes | Close old listener/clients, remove old advertisement, retain explicit sharing intent, show Waiting for Wi-Fi. Rebind/re-advertise on a suitable new Network. |
| NSD transient failure | Retry with backoff within the active session; distinguish listening from discoverable. |
| Missing/revoked permission | Pause with an actionable permission explanation; no blind retry loop. |
| Port unavailable | Show address-in-use separately; evaluate an advertised alternate port with both apps before adopting fallback. |
| Samples become stale | Clear live BPM immediately; never replay it. Invalidate affected receivers once per stale episode, not every watchdog tick. Gate subscriptions during recovery. |
| Same AirPods recover locally | Resume only on fresh real samples while local ownership is valid. |
| Another device takes AirPods | Stop sharing with explicit reason; do not recover by taking them back. |
| Long sensor outage | Keep a truthful paused session with bounded/event-driven retries and low resource use; Stop remains available. No misleading SEND_FAILED replacement. |
| Stop or sharing method change | Cancel recovery and prevent late callbacks from recreating anything. |

Use callback-driven network selection and revalidate LinkProperties when binding.
Do not require internet access for LAN sharing. Bound handshake and incomplete
frame times, request rates and blocked writes; evict only the unhealthy client.
Keep command responses reliable; use a latest-fresh-sample slot rather than a BPM
backlog. Serialize subscription state so no new measurement follows unsubscribe
acknowledgement. Validate receiver gap/reconnect behavior in both apps before
changing the current disconnect-on-gap wire behavior.

EveryPods can restore advertising and accept subscriptions; it cannot force a
third-party app to rediscover or resubscribe. Test and document that boundary.

### 4. Production UX and diagnostics

Use distinct statuses: Waiting for AirPods, Waiting for Wi-Fi, Discoverable,
Receiver connected, Sharing live, Reconnecting, and Action required. Home,
settings and notification consume the same state. A connected receiver without
fresh samples must not look like live sharing.

For peers, show Reconnecting automatically / Waiting for Bluetooth / Peer app
unavailable where actually known, plus last confirmed status and a manual retry
fallback. Don't label an unknown state as peer released or definitely out of range.

Record bounded diagnostic events: transitions, generation, retry reason/delay,
last reply/sample age, ownership source, passive acquisition decision and cleanup.
Avoid continuous raw heart-rate history or full hardware identifiers. Remove test
labels and enable DIRCON independently of audio relay preview only after gates pass.

## Validation and release gates

1. Deterministic lifecycle tests: Stop during connect/register/retry, stale callback
   after replacement, duplicate connections, split/joined peer messages, missed
   heartbeat, same-name peers, peer removal, Bluetooth toggle, ownership unknown,
   conflicting claims, delayed grants, and no automatic grab while peer offline.
2. DIRCON socket/network fault tests: malformed/partial frames, unsubscribing while
   publishing, slow/non-reading client, full client slots, IP change, NSD failure,
   port conflict, Wi-Fi return, repeated stale samples and zero stale replay.
   Current loopback tests bypass start(), so they do not cover network/NSD setup.
3. Pixel + Xiaomi physical matrix: alternate the audio holder and elected dialer;
   move out of range and return while music plays and screens are off; open the
   case while coordination is down; exercise calls, app process death, identical
   device names, Bluetooth off/on, three peers and legacy Windows interoperability.
4. DIRCON physical matrix in both Incyclist and Zwift: active exercise with Assioma,
   receiver restart, Wi-Fi off/on and AP/IP changes, sensor interruption, handover,
   screen off, permission revocation, Stop during recovery and a two-hour session.
5. Proposed initial target: peer READY within 30 seconds of sustained reachability
   with both services running; DIRCON advertised within 10 seconds of suitable
   Wi-Fi returning. Measure p50/p95 and worst-case over at least 20 cycles per
   direction. These are targets, not current guarantees. Receiver resubscribe
   latency is measured separately. Require zero unintended takeovers, no audible
   retry-induced playback interruption, no stale BPM and no accumulating resources.
6. Run unit suites, debug/release lint, build/minification and localization checks.
   Roll out to test builds first, then production. Suggested reviewable changes:
   passive safety gate; peer lifecycle/framing; negotiated ownership and recovery;
   DIRCON recovery; production UX and release integration.

## Scope and evidence limits

No app code, device settings, commits or PRs were changed by this review. No new
physical reproduction or test run is claimed. The prior manual-only policy was
a response to observed radio contention; the present request supersedes that UX
choice, but the audio protection remains a release requirement.

Being able to connect to AirPods does not prove that the other phone's RFCOMM
service is reachable: these are different links, and the peer app/listener can
be unavailable. It does mean we should attempt safe coordination and must not use
coordination failure as permission to seize the headset.

Platform references:
- https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
- https://developer.android.com/reference/android/bluetooth/BluetoothSocket
- https://developer.android.com/develop/connectivity/network-ops/reading-network-state
