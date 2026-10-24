# BT handover setup synchronization

Adding a BT handover entry from one app creates the reciprocal entry in the other
app. Removing it from either app removes both entries. Android Bluetooth bonding
is a prerequisite and is preserved. On first BT setup, each app enables handover,
media-start takeover, and incoming-call takeover. These preferences remain local;
later opt-outs survive acknowledgements, reconnects, additional peers, and re-adds.
Upgrading existing BT records preserves their previous choices.

## Transport and lifetime

`BtHandoverSync` runs with `AirPodsService`. It uses secure RFCOMM service UUID
`de51a5b3-4140-4fb7-a524-967379181773`. This setup endpoint accepts only Android-bonded
devices and does not require existing EveryPods peer membership. The regular peer
channels retain their saved-capability checks. Wi-Fi relay verification is separate.

Each connection carries one request and one response, then closes. Messages have a
big-endian 32-bit byte length followed by UTF-8 JSON; lengths must be 1–1024 bytes.
An exchange is closed after 10 seconds. At most two outgoing exchanges run at once;
incoming work is also bounded. Offline requests retry with backoff up to 60 seconds.
Service destruction closes the listener and outstanding sockets. Bluetooth changes
restart listening without discarding pending requests.

## State and acknowledgement

Version 1 messages contain exactly `version`, `revision`, `origin`, and `enabled`.
`origin` is a persistent, installation-specific UUID. A local explicit Add or Remove
increments the last observed revision for that Bluetooth peer and atomically commits
the capability, state, and pending request in `device_connections_v1`.

States are ordered by revision, then removal before creation, then origin UUID.
Both peers therefore converge without relying on wall clocks. The receiver commits
the winning state before replying with it. A reply clears only the pending change
it acknowledges or supersedes. Older replies cannot clear newer pending changes.
Duplicate messages are idempotent and do not create new outgoing requests.

Deletion records survive reconnection and process restart so old creation requests
cannot resurrect removed entries. A new explicit Add can create a newer revision.
Existing entries from older builds remain usable; simply connecting does not
advertise them as new setup requests.

The UI shows pending setup/removal. Newly added BT entries do not authorize handover
until acknowledged. Peer coordination and call takeover require a saved, acknowledged BT peer,
but it need not be online. Playback takeover is independent of peer setup and
the cross-device coordination switch: local playback can connect the saved
AirPods with zero peers, using its own playback takeover preference. Reachable
BT handover peers are still asked to release before connecting. Only reachable
holders participate in the call-takeover holder-capability check. Passive lid-open
acquisition still requires the existing ownership/reservation checks.
Removing BT preserves Wi-Fi relay and vice versa. Removing the final capability
closes that peer's normal coordination transports and removes active peer membership;
the setup endpoint and durable deletion record allow later synchronization or re-add.

## Validation

Unit tests cover reciprocal creation, offline removal, persisted pending state,
replayed messages, delayed acknowledgements, simultaneous creation/removal, explicit
re-addition, independent peers, schema validation, and membership bootstrapping.
Physical validation must additionally check one-sided Add and Remove, offline retry
across an app restart, and preservation of Wi-Fi relay authorization.

On 2026-10-23, Pixel 10 and Xiaomi 24091RPADG passed one-sided removal and creation
from Xiaomi. With Pixel Bluetooth off, another removal remained pending after a
Xiaomi app restart. Restoring Pixel Bluetooth applied and acknowledged it. Adding
from Pixel then restored both BT entries. Wi-Fi relay entries and authorization were
preserved throughout. These checks validate connection setup and synchronization;
they do not constitute a new AirPods audio handover or relay playback test.
