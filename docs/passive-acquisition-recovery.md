# Passive acquisition recovery: design, implementation plan and validation

## Observed failure

On 29 October 2026 the Pixel granted Xiaomi a reservation and acknowledged START
at 14:59:50. Its participant state remained IN_PROGRESS. Xiaomi later connected
through offline recovery at 15:45, but that connection had no matching acquisition
request ID. Pixel subsequently denied 49 requests at 16:23–16:26 with BUSY.

## Safety and progress rules

A timer alone cannot prove that an asynchronous Bluetooth command has stopped.
Keep actual issued attempts fenced until headset ownership or a terminal profile
failure is observed. A normal pre-command cancellation, however, is already proof
that the requester will not issue that command; participants must accept it even
after START_ACK. Late callbacks and ACKs must not resurrect a cancelled request.

1. Reserve → ready → validate → commit immediately before the OS command.
   Authorization on the main handler does not itself mean a command was issued.
2. ACQUIRE_CANCEL means definitely not issued (or an explicitly observed terminal
   failure after issuance). A matching authenticated cancel clears PREPARING and
   IN_PROGRESS reservations. Match requester, process session and membership.
3. A fresh authenticated HELD observation for the reserved headset and requester
   resolves that participant reservation, irrespective of its old acquisition ID.
   Ownership checks still prevent another passive grab while that peer holds it.
4. Completion is reconciled on either local A2DP success or a terminal A2DP
   CONNECTING/CONNECTED → DISCONNECTED transition. Uncertain states, elapsed time,
   lost transport, and ordinary lid close after issuance are not failure evidence.
5. Bound pre-command waits. An expired preparation can be cancelled and replaced;
   an issued request cannot be replaced or replayed. Participant and requester
   roles cannot simultaneously authorize competing commands on the same phone.
6. Preserve the existing v2 wire format and compatibility with installed peers.
   Existing senders already suppress CANCEL after issuance; no timeout or relaxed
   permission grants are introduced. Both devices should be updated to gain the
   full lifecycle repair.
7. Journal request/grant fingerprints, phases, age, command-issued status,
   cancellations and resolution reasons. Fingerprints correlate both devices
   without writing raw installation/session identifiers.

## Implementation order

- Add pure coordinator lifecycle transitions and regression tests first.
- Wire authenticated cancellation and headset ownership observations into them.
- Split validation from command commitment in CrossDevice/AirPodsService; carry
  the exact request through delayed callbacks and fence cancellation/replacement.
- Observe terminal profile outcomes and reconcile successful attempts during
  snapshot construction, so a lost success callback cannot retain a local lock.
- Add lifecycle diagnostics without changing ownership revision semantics.
- Run the full unit suite, build, lint, localization and diff checks; install on
  reachable devices with matching certificates while preserving app data.

## Validation matrix

- Replay START/ACK → cancel before command → repeated new requests: no BUSY wedge.
- Drop the last START_ACK: requester timeout cancellation frees participant.
- Reorder or duplicate cancel, grant and START_ACK; stale callbacks cannot commit.
- Cancel with wrong requester, session, membership or request ID: reservation stays.
- Cancel after actual issuance: ordinary cancellation remains blocked locally.
- Completed offline/manual connection with no matching request ID resolves old
  participant state only for the authenticated reserved requester/headset.
- UNKNOWN, RELEASED, another requester, or another headset never supply HELD proof.
- Observe native connection failure after issuance: exactly one terminal cleanup;
  a subsequent lid cycle can reserve again. No timer-only release.
- Expired preparation, late callback, lost transport, multiple peers, duplicate
  completion and simultaneous requester/participant roles preserve exclusion.
- On-device: journal survives update; peer protocol remains synchronized; check
  reservation exchanges and A2DP results across fresh case cycles. Clearly record
  which physical cases actually ran, and which still need user participation.

## Implemented recovery details

Unacknowledged terminal cancellations are retained in the requester coordinator
and replayed before the next request and during ownership refresh. They are
retired only after every participant has granted a subsequent round. Exact IDs,
sessions and membership digests prevent a delayed cancellation from clearing a
new grant. A new request by itself never supersedes an issued reservation.

The A2DP profile callback validates first, resolves the reflective method, then
commits immediately before invoking it. Method lookup failure or an absent
callback therefore remains cancellable. Duplicate callbacks cannot commit twice.
An explicit `connect()` false return on the non-policy path is a rejected command;
a later rejection after observed CONNECTING cannot cancel the active connection.
The HEADSET callback requires the original committed request to remain current.

Repeated cancellation, terminal failure and completion are idempotent. Requester
and participant roles exclude one another. Snapshot construction reconciles a
confirmed local audio connection while retaining the matching acquisition proof
in the first outgoing HELD snapshot for compatibility. Optimistic proxy-unavailable
ownership caches are never completion proof.

The v2 codec and wire fields are unchanged. Existing peers remain compatible,
but both phones need the new participant cancellation handling for full repair.
No retention timer, process death or transport failure alone proves that an
already issued native command has failed. Such uncertain attempts remain fenced
until an explicit rejection, final profile failure, or actual HELD ownership is
observed. Restart/session loss with no conclusive native outcome remains a
conservative recovery limitation; it is not silently treated as permission to
start a competing command.

## Validation results — 29 October 2026

- Full debug unit suite: **614 tests; zero failures, errors or skips**.
- Debug APK assembly and debug lint: passed (existing lint baseline/warnings remain).
- Localization validation: complete resources in all 11 locale folders.
- `git diff --check`: passed.
- `AcquisitionRecoveryTest` has 14 wire/state-machine regression tests, including
  the recorded START_ACK/cancel failure, lost ACK/cancel, three-member quorum,
  rejected native commands, late callbacks, mismatched cancel identity, and
  reservation recovery from independently observed headset ownership.
- Pixel: final APK updated in place with matching signing key and preserved data.
  App/service running, history retained and writing, peer connection reaches v2
  Ready with accepted RELEASED/IDLE snapshots. No startup crash or journal write
  failure observed in the post-install log window.
- Xiaomi: final APK updated in place at 17:11, preserving app data and the day's
  journal. App/service running and receiving fresh accepted RELEASED/IDLE peer
  snapshots. The user performed the requested close/open/ear-in test and confirmed
  automatic connection. A2DP reached CONNECTED at 17:11:51.431; HEADSET reached
  CONNECTED at 17:11:51.931. At 17:13:14 A2DP was still the active media device,
  with no intervening profile or ACL disconnect in the new process history.
  This exceeds the earlier approximately 32-second drop window.
  This trial was an AirPods-initiated native Bluetooth connection (ACL at
  17:11:50.815), preceding the app lid callback at 17:12:02.455. The app correctly
  skipped an unnecessary acquisition because it already held the audio link.
  Therefore this physical trial validates automatic reconnection and short-term
  stability, not the repaired distributed reservation exchange. That exchange is
  validated by the 14 two-/three-endpoint wire/state-machine regression tests.
  Direct Wi-Fi ADB from the Mac remains unavailable.


## Follow-up physical review at 17:20–17:24

The user subsequently exercised the distributed reservation path in both
directions. All three matching request fingerprints (fc03a5bf5777, 990700a49d48,
8ab1d11b23d1) reached RESOLVED on both phones, with a single native command commit
per requester. Lid-to-A2DP times were 4.027 s and 4.893 s on Pixel, and 2.332 s on
Xiaomi. Offline recovery and both Bluetooth-toggle scenarios also completed.
There were no BUSY denials for these attempts. This supplies physical validation
of the repaired exchange beyond the earlier native-only connection trial.

The review also found remaining peer heartbeat/reconnect churn and Pixel
notification update throttling. These did not prevent the observed reconnections,
but mean the overall internal behavior is not completely clean. Full trace and
validation limits are in `tmp/case-open-review-2026-10-29/repeated-tests/review.md`.
