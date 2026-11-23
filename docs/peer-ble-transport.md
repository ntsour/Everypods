# Peer links over Bluetooth LE (GATT)

Status: proposed, 2026-11-22; phase 1 in progress (spike passed). It adds a second
transport under the peer mesh (`peer-mesh-spec.md`). It carries the same
messages as RFCOMM: the legacy 4-byte packets in phase 1 (all the Mac speaks
today) and v2 EPH2 frames later.

## Why

A Mac cannot hold a dependable peer link over classic Bluetooth (RFCOMM):

- **Dialling a phone fails after the phone app restarts.** Android gives the
  app's listener a new RFCOMM channel on every start (seen 27 → 30 → 19 → 21 →
  29). macOS keeps dialling the old channel from its SDP cache, and there is no
  public API to refresh it; `performSDPQuery` rarely completes. Recovery took
  2+ minutes or never happened.
- **Accepting a link does not work either.** bluetoothd parks an incoming
  RFCOMM channel (`No sessions found saving incoming connection info`) and
  hands it to the app only when the app registers for channel-open
  notifications *after* it arrived. Registering again also drops the pipe of
  any outgoing open in progress in the same process. Seven phone-app restart
  trials with the phone dialling the Mac (2026-11-22) gave **0 relinks within
  60 s**. The one Windows → Mac inbound link seen earlier was delivered only
  because the Mac app happened to restart 12 s later.

Both are in IOBluetooth, which Apple no longer develops. CoreBluetooth (LE)
is the Bluetooth API Apple maintains, it has no channel numbers or SDP cache,
and a phone that restarts is found again as soon as it advertises.

Handover traffic is small (v2 frames up to 4 KB, mostly < 200 bytes, a few
per minute), so GATT throughput is not a concern.

## Principle

Same as the mesh spec: rules never branch on "phone" or "computer". Which
transport a pair uses follows from **capabilities**:

| Capability | Meaning |
|---|---|
| `gatt-peripheral` | Advertises the EveryPods peer service and accepts GATT links |
| `gatt-central` | Scans for the service and opens GATT links |

Planned support:

| | Android | Mac | Windows |
|---|---|---|---|
| `gatt-peripheral` | yes (phase 1) | later (CBPeripheralManager) | later (GattServiceProvider, adapter-dependent) |
| `gatt-central` | no | yes (phase 1) | later |
| RFCOMM v2 listen / dial | yes / yes | no / yes (old phones only) | yes / yes |

## 1. Choosing the transport for a pair

1. If one side has `gatt-central` and the other `gatt-peripheral`, the central
   side connects over GATT. If both sides have both, the side with the smaller
   `installationId` connects; the other only accepts.
2. Otherwise the pair uses RFCOMM v2 as in the mesh spec (phone ↔ phone,
   Windows ↔ phone today).
3. A central that has a ready GATT link to a peer does not dial that peer over
   RFCOMM. If both links come up anyway, the mesh spec's duplicate rule
   (smallest `(initiatorId, connectionId)` from HELLO) keeps one.
4. Fallback: a central that has not seen the peer advertise for 30 s, while
   the peer is configured and has no ready link, may dial it over RFCOMM (this
   keeps phones running builds without the GATT service working).

## 2. Advertising

The peripheral advertises while cross-device coordination is enabled, at
least one peer is configured, and Bluetooth is on:

- **Advertisement:** flags + the 128-bit service UUID
  `78fc4c78-0bdc-435d-b4a8-7d8b5f0c0a04` (`EVERYPODS_PEER_GATT`). Connectable.
- **Rate:** `ADVERTISE_MODE_LOW_LATENCY` (~100 ms) while no configured central
  is linked, `ADVERTISE_MODE_LOW_POWER` (~1 s) once one is. The spike measured
  1.3–2.9 s Mac relinks at low latency against 3.7–7.3 s at balanced.
- **No identity in the advertisement.** The service UUID says "an EveryPods
  device", not which one. The central connects and learns the identity from
  the encrypted link (§4). In a household with a few devices that costs one
  short connection each; it keeps the advertisement free of anything that
  could track one person. (A rotating member token, as in the first draft of
  this document, can be added later if connecting to every advertiser becomes
  a problem.)
- Failures are retried as bitchat-android does: `TOO_MANY_ADVERTISERS` and
  `INTERNAL_ERROR` with a growing delay (2 s, 4 s, … 30 s);
  `ALREADY_STARTED` ignored; `DATA_TOO_LARGE` and `FEATURE_UNSUPPORTED` logged
  once and given up (the RFCOMM path stays).
- A device whose adapter reports `isMultipleAdvertisementSupported == false`
  (no peripheral role) does not advertise; computers reach it over RFCOMM.

## 3. GATT service

One primary service `EVERYPODS_PEER_GATT` with two characteristics:

| Characteristic | UUID | Properties | Permissions |
|---|---|---|---|
| `RX` | `…0a05` | write, write without response | write **encrypted** |
| `TX` | `…0a06` | notify; CCCD read/write **encrypted** | — |

Both carry a **byte stream** of records (§4); a record may span several
writes or notifications. Each side chunks to the current `MTU − 3`
(Android: `onMtuChanged`, default 23; macOS:
`maximumWriteValueLength(for: .withoutResponse)`).

Flow control:
- **Central (CoreBluetooth):** write only while `canSendWriteWithoutResponse`
  is true; queue the rest and drain on `peripheralIsReady(toSendWriteWithoutResponse:)`.
- **Peripheral (Android):** one `notifyCharacteristicChanged` per central in
  flight; send the next chunk from `onNotificationSent`.

Order is preserved within one connection.

## 4. Security and records

### 4.1 Encryption from the existing bond

Peers are already paired over classic Bluetooth (the mesh spec's trust
boundary). Android and macOS derive LE keys from that bond
(cross-transport key derivation), so an **encrypted** characteristic works on
a bonded pair with no prompt. The spike confirmed it on Pixel 10 + MacBook Air
(encrypted CCCD and RX, no dialog). A pair without derived LE keys gets the
operating system's one-time LE pairing prompt on first access; nothing in the
app changes.

Consequences:
- A device that is not bonded cannot subscribe or write.
- **The peripheral knows who the central is:** with LE keys the central's
  address resolves to its identity address, which is the same public address
  the peer is configured under (the spike saw the Mac as `68:5E:DD:55:7E:17`).
  The phone accepts records only from a configured, coordination-authorized
  peer, exactly as its RFCOMM server does.
- **The central learns who the peripheral is from HELLO** (§4.3): CoreBluetooth
  exposes no Bluetooth address. The Mac matches HELLO's Bluetooth name to a
  configured peer and then remembers that peer's `installationId`, so a later
  rename does not break the match.

No application-level cryptography is needed in phase 1.

### 4.2 Records

```
type (u8) ‖ length (u16, big-endian) ‖ payload (length bytes, ≤ 4096)
```

| Type | Name | Payload | Sent by |
|---|---|---|---|
| `0x01` | HELLO | UTF-8 JSON `{"v":1,"id":installationId,"name":bluetoothName,"caps":[...]}` | both, first record |
| `0x02` | LEGACY | one 4-byte legacy packet (`00010001`, …) | both |
| `0x03` | PING | 4-byte sequence | central |
| `0x04` | PONG | the PING's 4 bytes | peripheral, at once |
| `0x05` | V2 | one EPH2 frame (reserved for when the Mac speaks v2) | both |

An unknown type is skipped by its length. A length above 4096 or a HELLO
that does not parse closes the session (the central cancels; the peripheral
drops its state for that central).

PING/PONG are transport-level: answered by the link itself, never by the
handover logic, so a peer that is busy releasing the AirPods still proves it is
alive.

### 4.3 Session

1. Central connects, discovers the service, subscribes to `TX`.
2. Peripheral, on the subscribe (CCCD write), checks the central is a
   configured peer and sends HELLO.
3. Central sends HELLO. Both mark the link **ready** once they have the other's
   HELLO.
4. Peripheral announces its AirPods state (LEGACY `00010001`/`00010000`), as
   its RFCOMM server does for a new client.
5. Central PINGs every 2 s. A PING unanswered for 3 s means the service went
   away under a link macOS kept: rediscover on the same connection (5 s
   window), then reconnect.

## 5. Lifecycle (what makes it reliable)

Each rule below was either hit in the spike or is handled the same way in
bitchat's iOS/macOS client (`jackjackbits/bitchat`, `BLEService+LinkLayerCentralRole.swift`)
and Android client (`permissionlesstech/bitchat-android`, `BluetoothGattServerManager.kt`).

**Central (Mac, CoreBluetooth)**
- Scan with a service filter whenever a configured peer has no ready link.
  CoreBluetooth's `connect` never times out; a pending connect completes when
  the phone is reachable.
- **Phone app restarted under a live link:** macOS keeps its LE link, so there
  is no disconnect. Handle `peripheral(_:didModifyServices:)` and rediscover on
  the same connection. If that notice does not come, the unanswered PING (3 s)
  triggers the same rediscovery.
- **Bluetooth off / resetting:** CoreBluetooth invalidates every `CBPeripheral`.
  Drop all link state without issuing commands (they are rejected while not
  powered on); start again on `.poweredOn`. Keeping the old object made the
  spike ignore the phone for 8 hours.
- **A phone macOS is already connected to is not reported by a scan.** While
  unlinked, also poll `retrieveConnectedPeripherals(withServices:)` every 2 s
  and attach to such a link.
- After a disconnect: reconnect directly to the same peripheral and restart the
  scan (its LE address may have rotated).
- Only one session per peer: a second peripheral that HELLOs with an
  `installationId` already linked is disconnected.

**Peripheral (Android)**
- Open the GATT server, wait for `onServiceAdded`, then advertise.
- Restart server and advertising on Bluetooth on; close both on Bluetooth off,
  service stop, or when coordination is disabled.
- Never `cancelConnection` a central just because it is unknown: macOS shares
  one LE link between its own services and ours. Ignore its writes instead.
- A central that sends nothing for 30 s loses its **ready** state (not the
  link); a new subscribe or HELLO restores it.
- Runs inside the existing foreground service, so advertising continues in the
  background (Android 12+ requires that).

## 6. Work per client (phase 1)

**Android** (`utils/peer/`):
- `PeerGattRecords`: record encoder and stream decoder (pure, unit-tested).
- `PeerGattServer`: GATT server, advertiser, per-central session (MTU, chunked
  notification queue, decoder, HELLO, ready state, PONG, 30 s idle).
- `CrossDevice`: a ready GATT session counts as a link to that peer
  (`isConnectedTo`, `sendTo`, `broadcast`); incoming LEGACY records go to
  `processPacket(packet, mac)`; on ready, announce the AirPods state like the
  RFCOMM server; on end, `onPeerTransportDisconnected`. Same 20 s status
  keep-alive and liveness expiry as the RFCOMM server.
- Request `BLUETOOTH_ADVERTISE` with the other Nearby-devices permissions
  (`AppPermissionsScreen`). Without it the phone simply does not advertise.

**Mac**:
- `PeerGattRecords.swift`: the same codec, with a small test runner.
- `PeerGattCentral.swift`: scanning, connecting, rediscovery, write queue,
  HELLO, PING, matching HELLO to a configured peer.
- `PeerRegistry`: per peer, send over the GATT session when it is ready, else
  over RFCOMM; pause the RFCOMM dialler for a peer while its GATT session is
  ready (one link per pair, and no more dialling stale channels); report
  "connected" if either transport is up.

**Windows**: unchanged in phase 1 (its RFCOMM links work). Later: a central
using `Windows.Devices.Bluetooth.GenericAttributeProfile`, same records.

## 7. Plan

1. ~~Spike~~ (done, results below).
2. Android: `PeerGattRecords` + tests, `PeerGattServer`, `CrossDevice`
   integration, permission request. Branch `feat/peer-gatt-transport`.
3. Mac: `PeerGattRecords` + tests, `PeerGattCentral`, `PeerRegistry`
   integration; remove the spike.
4. Hardware: phone-app restart, Mac restart, Bluetooth off/on both sides,
   sleep/wake, handover both ways (Mac plays → Mac takes the AirPods; phone
   plays → Mac releases; call refusal), on the Pixel, the second phone and the
   tablet. A device without peripheral support must fall back to RFCOMM.
5. Later: Mac speaks v2 over the same records (type `0x05`); Windows central;
   Mac peripheral for Mac ↔ Windows.

## Spike results (2026-11-22, Pixel 10 + MacBook Air)

Android echo peripheral (`PeerGattSpike`) and a CoreBluetooth central in the Mac app, ping every 2 s.

| Check | Result |
|---|---|
| Phone app force-stop + restart | 5/5, Mac re-subscribed 0.58–0.66 s after the app started |
| Phone Bluetooth off/on | 2/2, ~1 s after advertising restarted |
| Mac app restart | 3/3, 1.3–2.9 s after launch (low-latency advertising; 3.7–7.3 s with balanced) |
| Mac Bluetooth off/on | 3/3, 0.9–1.8 s after Bluetooth on |
| Mac sleep/wake | 1/1, 1.7 s after wake |
| AirPods playing on the phone with the link pinging | no audible dropouts (user) |
| Out of range and back | not tested (the link never dropped while walking around the flat) |

What the central must do (all learned in the spike):
- macOS keeps its LE link to the phone across a phone-app restart, so there is no disconnect: handle
  `didModifyServices` and rediscover on the same connection.
- If that notice does not come, an unanswered ping after 3 s triggers the same rediscovery.
- CoreBluetooth invalidates every peripheral on Bluetooth off; drop it, or later sightings of the same phone are
  ignored.
- A scan does not report a phone macOS is already connected to; while unlinked, also poll
  `retrieveConnectedPeripherals(withServices:)` and attach.
- The app needs `BLUETOOTH_ADVERTISE` granted at runtime (it was declared but never requested).
- Advertise at low latency only while no central is linked, to save battery.

## Open questions

1. Devices without cross-transport key derivation: confirm the one-time LE
   pairing prompt appears and is enough (second phone, tablet).
2. Some OEMs stop advertising for background apps despite a foreground
   service. Track per device; RFCOMM remains the fallback.
3. Out-of-range-and-back was not exercised in the spike.
