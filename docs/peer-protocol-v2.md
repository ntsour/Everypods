# EveryPods peer protocol v2

Status: EPH2 framing, strict message validation, identity binding, connection
arbitration, heartbeat synchronization, ownership snapshots, and passive
reservation messages are wired between updated Android peers. Unit tests cover
the codec, schemas, arbitration policy, ownership store, retry policy, and
reservation state machine. Windows interoperability and physical Bluetooth
recovery remain unverified.

## Endpoint and frame

The v2 RFCOMM service UUID is 97ce0b4d-8e2f-4a6d-b6cd-d69d362e75ac.
The legacy UUID 1abbb9a4-10e4-4000-a75c-8953c5471342 and its four-byte
commands remain unchanged. Never send a v2 frame on the legacy socket.

Each v2 frame is EPH2 (4 ASCII bytes), version 2 (u8), type (u8), payload
length (u32 unsigned big-endian), then exactly that many UTF-8 JSON bytes. The
payload limit is 4096 bytes. Type IDs are fixed:

| ID | Type |
|---:|---|
| 1 | HELLO |
| 2 | SNAPSHOT |
| 3 | PING |
| 4 | PONG |
| 5 | ACQUIRE_REQUEST |
| 6 | ACQUIRE_GRANT |
| 7 | ACQUIRE_DENY |
| 8 | ACQUIRE_CANCEL |
| 9 | RELAY_DATA |
| 10 | ACQUIRE_START |
| 11 | ACQUIRE_START_ACK |

Readers incrementally decode the byte stream. Unknown magic, version, type,
oversized payload, invalid UTF-8, malformed JSON, missing/unknown schema fields,
or invalid values close only that peer. There is no magic-byte scanning or
resynchronization after an error.

All identifiers are lowercase canonical UUID strings. Objects reject unknown
fields and duplicate JSON keys. Integers are nonnegative signed 64-bit values;
booleans are JSON booleans, and enum strings are case-sensitive.

## Message schemas

Every frame payload is one JSON object. The following schemas are exact; null
is permitted only where shown.

### HELLO (1)

    {"installationId":"uuid","sessionId":"uuid","capabilities":["acquire-v1","snapshot"],"initiatorId":"uuid","connectionId":"uuid"}

Capabilities are unique, sorted strings from the supported capability registry.
installationId is stable for this app installation. sessionId changes on
process start. initiatorId is the installation that opened this RFCOMM
connection. connectionId is random per dial. The receiver binds installation
identity to the configured Bluetooth address after validating the Bluetooth
remote address against the configured peer set; an unconfigured address cannot
claim an identity.

### SNAPSHOT (2), also nested in PONG

    {"sessionId":"00000000-0000-4000-8000-000000000002","revision":0,"headsetId":null,"ownership":"unknown","audioUse":"unknown","lastOwnerId":null,"lastOwnerRevision":null,"acquisition":null}

headsetId identifies the target AirPods identity when known. ownership is
measured from the current local A2DP/AACP route, never the persisted lease.
audioUse describes media/call use on that route only when observable.
lastOwnerRevision is null exactly when lastOwnerId is null. acquisition is null
or {"requestId":"uuid","requesterId":"uuid","phase":"preparing|in_progress"}.
Revision is monotonic within one session and advances when snapshot state
changes; a heartbeat may repeat the current revision with a newly received
local freshness time. Session changes invalidate the prior snapshot and all
grants. Elapsed timestamps are never sent across devices.

### PING (3), PONG (4)

PING is {"nonce":"uuid"}. PONG is {"nonce":"uuid","snapshot":{...SNAPSHOT fields...}}.
A PONG must echo the outstanding nonce and include a currently measured
snapshot. Both Android sides issue a heartbeat every ten seconds, close after
two missed response intervals, and require a matching PONG plus an accepted
snapshot before publishing protocol-v2 READY. The initial handshake has a five
second deadline. A successful write is not a heartbeat response.

### ACQUIRE_REQUEST (5)

    {"requestId":"uuid","headsetId":"uuid","requesterId":"uuid","requesterSessionId":"uuid","membership":["uuid"],"membershipDigest":"64-lowercase-hex","snapshotRevisions":{"installation-uuid":0}}

Membership is a sorted, unique list including the requester and grantor. Its
digest is SHA-256 over UTF-8 canonical UUIDs joined by one newline, with a final
newline. snapshotRevisions has exactly one entry per membership participant
whose current snapshot was used; missing/unknown revision denies the request.

### ACQUIRE_GRANT (6)

Same request identity and membership fields, plus grantorId, grantorSessionId,
and grantId (UUID). The grant is valid for five seconds on the grantor's
monotonic clock and only for this request, headset, requester session and
membership digest. It reserves the grantor but does not report RELEASED or
authorize audio connection by itself.

### ACQUIRE_DENY (7)

Same request identity and membership fields, plus grantorId, grantorSessionId
and reason, one of HELD, UNKNOWN, STALE_SNAPSHOT, MEMBERSHIP_MISMATCH, BUSY,
UNSUPPORTED, SESSION_CHANGED.

### ACQUIRE_CANCEL (8)

    {"requestId":"uuid","requesterId":"uuid","requesterSessionId":"uuid","membershipDigest":"64-lowercase-hex"}

Cancellation releases only an unstarted matching reservation. An in-progress
grant remains UNKNOWN until the OS acquisition is resolved.

### RELAY_DATA (9)

    {"subtype":"battery|anc|ear_state","data":"base64"}

Decoded data is limited to 2048 bytes, then passed to the existing trusted relay
dispatcher. This frame cannot carry takeover or ownership commands.

### ACQUIRE_START (10), ACQUIRE_START_ACK (11)

Both carry requestId, headsetId, requesterId, requesterSessionId,
membershipDigest, grantId, grantorId, and grantorSessionId. START is accepted
only for an unexpired matching grant; the grantor atomically moves it to
IN_PROGRESS before sending ACK. ACK echoes the complete identity tuple.
Requester issues no headset command until every participant has acknowledged.

## Migration and connection arbitration

Both updated Android peers listen on the v2 UUID and keep the legacy listener.
The role-elected client maintains the legacy compatibility socket and attempts
the separate v2 socket through the supervisor's single global RFCOMM dial gate.
A v2 failure leaves legacy explicit handover available, but legacy peers cannot
grant passive lid acquisition. Healthy legacy links stay up while v2 negotiates.

For duplicate v2 dials, both ends choose the same lexicographically smallest
(initiatorId, connectionId) tuple after HELLO exchange. A stale losing-link
callback must not remove the winner or its snapshot. After negotiation, the
smaller installation UUID is the preferred dialer; a budgeted fallback is
allowed if it is not recovering. Bluetooth names are not used for v2 election.

Legacy control frames require an incremental four-byte decoder. Variable-length
legacy data framing remains disabled until verified packet lengths/fixtures are
available. An unknown legacy payload closes that link and invalidates ownership
synchronization rather than being interpreted as a command.

V2 acquisition messages are dispatched only after HELLO identity and capability
negotiation. Passive headset connection requires a fresh all-peer snapshot
quorum plus a final local policy recheck; reconnecting a peer never requests or
performs headset takeover. This checkout has no Windows v2 client or fixtures,
so Windows interoperability is unverified. Hardware timing, range-return
recovery, and audio contention still require the physical acceptance matrix in
`connection-hardening-implementation-handoff.md`.
