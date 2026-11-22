# Peer links over Bluetooth LE (GATT)

Status: proposed, 2026-11-22. Nothing here is implemented. It adds a second
transport under the peer mesh (`peer-mesh-spec.md`); the protocol on top (v2,
EPH2 frames, HELLO/SNAPSHOT/PING, ACQUIRE_*) is unchanged.

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

The peripheral advertises while EveryPods' service runs and Bluetooth is on:

- **Advertisement:** flags + the 128-bit service UUID
  `EVERYPODS_PEER_GATT` (new, to be generated). Connectable, low-power mode
  (Android `ADVERTISE_MODE_LOW_POWER` ~1 s interval; balanced while no linked
  central is connected and the app just started).
- **Scan response:** service data for that UUID: an 8-byte **member token**
  `HMAC-SHA256(meshKey, "adv" ‖ installationId ‖ window)[0..8]`, where
  `window = floor(unixTime / 900)`.

The token lets a central recognise members without connecting and without
the advertisement carrying a stable identifier: it changes every 15 minutes
and is meaningless without `meshKey`. The central checks each configured
member's `installationId` for the current and adjacent windows (clock skew).

Android rotates its LE address, so the central never relies on the address or
on CoreBluetooth's peripheral identifier across rotations; it identifies the
peer by token and then by authenticated HELLO.

## 3. GATT service

One primary service `EVERYPODS_PEER_GATT` with two characteristics:

| Characteristic | Properties | Direction |
|---|---|---|
| `RX` | write without response | central → peripheral |
| `TX` | notify | peripheral → central |

Both carry a **byte stream** of records (§4); a record may span several
writes or notifications. The central requests the largest MTU (macOS
negotiates up to 185–527; Android peripheral accepts up to 517) and chunks to
`MTU − 3`.

Flow control: the central writes only when `canSendWriteWithoutResponse` is
true (CoreBluetooth) and the peripheral sends the next notification only after
`onNotificationSent`. Order is preserved within one connection.

No characteristic requires LE encryption or bonding: that would need an LE
pairing prompt on each device. Security is done in the record layer (§4).

## 4. Authentication and record layer

### 4.1 Mesh key

`meshKey` is 32 random bytes shared by all members. The first device to set up
a peer generates it; the setup exchange (`bt-handover-sync.md`, secure RFCOMM
`de51a5b3-…`, bonded devices only) gains a `meshKey` field in both directions,
and a device that already has one keeps it (the lexicographically smaller key
wins if two groups merge). Setup stays on classic Bluetooth in phase 1: it is
rare, user-started and can be retried, so the stale-channel problem matters
less there. Moving setup to GATT is phase 3.

### 4.2 Session handshake (after connect, before any v2 frame)

1. Central → `RX`: `HELLO_C = nonceC (16) ‖ installationIdC (16)`.
2. Peripheral → `TX`: `nonceP (16) ‖ installationIdP (16) ‖
   HMAC(meshKey, "P" ‖ nonceC ‖ nonceP ‖ idC ‖ idP)`.
3. Central checks the MAC and that `idP` is a configured member, then →
   `RX`: `HMAC(meshKey, "C" ‖ nonceC ‖ nonceP ‖ idC ‖ idP)`.
4. Both derive `k = HKDF-SHA256(meshKey, nonceC ‖ nonceP, "everypods-gatt")`,
   split into one key per direction.

A failed check closes the connection; the central ignores that peripheral
until its token window changes.

### 4.3 Records

`length (u16, big-endian) ‖ AES-128-GCM(k_dir, nonce = counter (u64), EPH2 frame)`.
The 16-byte tag is part of the ciphertext. Counters start at 0 per direction
and session; a repeated or skipped counter closes the link. The plaintext is
an unchanged EPH2 frame, so the v2 decoder, validation and limits are reused
as they are.

This also gives the RFCOMM path's missing property: a device in range that is
not a member cannot inject a takeover.

## 5. Lifecycle (what makes it reliable)

- **Central:** scans continuously for the service UUID (CoreBluetooth with a
  service filter works for a background app on macOS). On a member token with
  no ready link: `connect`. CoreBluetooth's `connect` never times out, so a
  pending connect completes whenever the peripheral is reachable. On
  disconnect: back to scanning; no backoff is needed because nothing is paged
  until the peer advertises.
- **Peripheral:** starts advertising when the service starts and on Bluetooth
  on; restarts advertising on any failure (`ADVERTISE_FAILED_*`) after 2 s. It
  keeps advertising while linked so a second central (another computer) can
  connect.
- **Liveness:** v2 PING/PONG every 10 s as today, plus the LE supervision
  timeout. Either closes the session.
- **Coexistence with AirPods audio:** after the handshake the peripheral
  requests low-power connection parameters (Android
  `CONNECTION_PRIORITY_LOW_POWER`, 100–125 ms interval) to keep airtime away
  from A2DP/HFP. Verified in the spike (§7).

Expected relink after a phone-app restart: advertising starts with the service
(< 1 s), the Mac's scan sees it within one interval (~1 s), connect +
handshake + HELLO ~1–2 s. **Target: < 5 s, every time.**

## 6. Work per client

**Android (peripheral)**
- `PeerGattServer`: `BluetoothGattServer` with the service, `BluetoothLeAdvertiser`
  with token rotation every 15 min. `BluetoothHeartRatePeripheral` is the
  existing pattern for a GATT server in this app. Permissions
  (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) are already declared.
- Record layer + handshake; feed decrypted EPH2 frames into the existing v2
  session code as a new `PeerConnection` transport.
- Setup exchange: `meshKey` field.

**Mac (central)**
- `PeerGattLink` on CoreBluetooth (`CBCentralManager`), one per member.
- Implement v2 on top (already required by the mesh spec, §9 Mac).
- Remove `PeerServer` (the RFCOMM listener does not work, see Why); keep
  RFCOMM dialling only as the §1.4 fallback for phones without the GATT
  service.

**Windows**: no change in phase 1 (RFCOMM works there). Later: central and,
where the adapter supports it, peripheral.

## 7. Plan

1. **Spike (go/no-go), ~1 day.** Android: advertise + GATT echo service.
   Mac: central that connects and echoes, no protocol. Pass if **20/20**
   phone-app force-stop/restart cycles relink in < 5 s, plus: Mac app restart,
   phone Bluetooth off/on, Mac sleep/wake, walking out of range and back, and
   no audible dropouts while AirPods play on the phone and the link pings
   every 10 s.
2. Record layer, handshake, `meshKey` in setup, with unit tests on both sides
   (shared test vectors for HMAC/HKDF/GCM).
3. v2 over GATT between Android and Mac; handover tests (Mac plays → Mac takes
   AirPods; phone plays → Mac releases; call refusal).
4. Mac drops the RFCOMM listener; Android keeps "dial computers" (#96 undo)
   only for computers without `gatt-central`.
5. Later: Windows central/peripheral, Mac peripheral (Mac ↔ Mac, Windows →
   Mac), setup over GATT.

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

1. Does CoreBluetooth's scan service filter match a UUID that appears only in
   the scan response? (Plan: UUID in the advertisement, token in the scan
   response, so it does not matter; confirm in the spike.)
2. Pixel and other Android peripherals while the phone also streams A2DP and
   runs the heart-rate peripheral: number of concurrent advertising sets and
   any audio impact (spike).
3. Some OEMs stop advertising for background apps despite a foreground
   service. Track per device; the RFCOMM fallback (§1.4) covers the gap.
