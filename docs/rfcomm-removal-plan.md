# Removing RFCOMM peer links: design, implementation and test plan

Status: accepted 2026-11-22, with the scope reduced after the spikes (see Decision below). It builds on [peer-ble-transport.md](peer-ble-transport.md). Phase 1 shipped as ntsour/Everypods#104 (Android GATT server) and ntsour/Everypods-private#7 (Mac LE central).

## Scope

This plan replaces every RFCOMM link between EveryPods peers (phones, tablets, Macs, Windows PCs) with the Bluetooth LE GATT link from phase 1.

Out of scope, and kept as they are:
- **The AirPods' own links:** the AACP L2CAP socket on Android, and the RFCOMM/SDP "wake" probe that the Windows `AirPodsConnector` uses to bring up the AirPods' ACL link. These talk to the AirPods, not to peers.

Where things stand after phase 1:

| Pair | Link today | Verified |
|---|---|---|
| Phone ↔ Mac | LE (Mac central) | Pixel 10 and a Xiaomi tablet: takeover, release, call refusal |
| Phone ↔ Windows | RFCOMM (Windows dials the phone) | Works with the phase-1 Android build |
| Phone ↔ phone | RFCOMM (elected by Bluetooth name) | Works |
| Mac ↔ Windows | **none** | Handovers work without coordination (macOS pauses when the AirPods leave) |
| Adding a peer (all) | Classic SDP plus the RFCOMM setup exchange | Works, but SDP caches go stale (seen with the Xiaomi) |

## RFCOMM inventory (Android)

Paths are under `android/app/src/main/java/io/automated/ventures/everypods/`.

| # | Where | Carries | Peers | Size | Replacement |
|---|---|---|---|---|---|
| 1 | `utils/CrossDevice.kt` legacy endpoint (`1abbb9a4…`) | 4-byte handover packets | Windows, phones | tiny; keep-alive every 20 s | `LEGACY` record (done for computers that act as central) |
| 2 | `utils/CrossDevice.kt` V2 endpoint (`97ce0b4d…`) | EPH2 frames (ownership, acquisition, case) | phones, V2 computers | ≤ 4 KB, mostly < 200 B | `V2` record (0x05, already defined) |
| 3 | `utils/CrossDeviceClient.kt`, `utils/peer/PeerConnectionSupervisor.kt` | client side of #1 and #2 | phone → phone only | retry loop | Android LE central (phase 2) |
| 4 | `relay/BtHandoverSync.kt` (`de51a5b3…`) | setup JSON `{version, revision, origin, enabled}` | phones, Mac (`SetupAuthorizer`) | ≤ 1 KB, rare | `SETUP` records (phase 3) |
| 5 | `relay/security/RelayBluetoothSignalHub.kt` (`6d3b1a70…`) | AES-GCM signalling for the Wi-Fi relay | configured peers | frames up to 128 KiB | `FRAG` records, see below (phase 4) |
| 6 | `utils/peer/RemoteCaseBluetoothTransport.kt` (`f8ad4759…`) | remote case evidence | configured peers | short, per case event | `V2` record with its own frame kinds (phase 4) |
| 7 | `utils/TraceUploadReceiver.kt` (`662d622f…`) | debug traces from Windows, up to 256 MB | Windows → phone, debug only | huge | Move off Bluetooth: the existing Wi-Fi relay path or `adb pull` (phase 4) |

The Mac also uses classic Bluetooth for setup: `PeerDiscovery.swift` (SDP), `ServiceLookup.swift` and `SetupAuthorizer.swift`. Windows has `BluetoothRfcommClient.cpp` and `PeerRegistry.cpp` (a client only: it dials phones).

## Design

### D1. Who is central (one link per pair)

| Pair | Central | Peripheral | Why |
|---|---|---|---|
| Phone ↔ computer | computer | phone | Done for the Mac. The Windows central APIs are mature; Windows as a peripheral needs adapter support (`IsPeripheralRoleSupported`) that many PCs lack (SimpleBLE `LocalPeripheralWindows.cpp`; Microsoft GATT server docs). |
| Phone ↔ phone | smaller `installationId` | the other | Replaces the election by Bluetooth name (`CrossDevice.electClient`), which elects nobody when two phones share a name. Same rule as peer-ble-transport.md §1. |
| Mac ↔ Windows | — | — | **Dropped after the spikes:** macOS denies LE pairing from the PC, and Windows gets no LE keys from the classic bond. The pair works without a link. |
| Mac ↔ Mac | — | — | Out of scope; would need the Mac peripheral role (dropped). |
| Windows ↔ Windows | none | — | Needs a Windows peripheral. The PCs coordinate through the phones, as today. Revisit if asked. |

Duplicate links are still possible (both sides connecting at once, or a stale link after an address rotation). They are resolved at HELLO:
- Keep the link whose initiator has the smaller `installationId`, and close the other with a reason.
- Tag every connection with a link id, so a late callback from a closed link cannot tear down the surviving one. This copies bitchat-android's `cleanupDeviceConnectionIfCurrent(linkID)` and bitchat's `BLERedundantLinkPolicy` ("keep the most recently connected").

### D2. Roles each client gains

| Client | Has | Adds |
|---|---|---|
| Android | GATT server | **GATT central** to phones with a larger `installationId` (phase 2) |
| Mac | LE central | nothing (the peripheral role was dropped) |
| Windows | RFCOMM client | nothing: it keeps RFCOMM to the phones |

### D3. Finding the peer

- **Android central:** connect directly to the bonded phone first, with `getRemoteDevice(classicAddress).connectGatt(ctx, false, cb, TRANSPORT_LE)` and a 25 s limit. If that fails (error 133 or timeout), run a filtered scan for the service UUID and connect to the result. For a standing reconnect on Android 14+, call `close()` and then `connectGatt(autoConnect = true)`.
  - Sources: Nordic `ConnectRequest.useAutoConnect(createDirectConnectionFirst)`, and its note that autoConnect only works with bonded devices.
  - **Gate S3:** whether a direct connect by classic address reaches a phone that advertises with a resolvable private address.
- **Windows central:** run a `BluetoothLEAdvertisementWatcher` filtered on the service UUID. Take `BluetoothAddress` and `BluetoothAddressType` from the event, then call `BluetoothLEDevice::FromBluetoothAddressAsync(addr, type)`. Fall back to the classic address. Map the device to a configured peer by HELLO `installationId`, not by address.
  - Sources: Bleak WinRT `client.py`; Microsoft Q&A on paired LE-privacy devices answering `Unreachable` on the first try.
- **Mac central:** unchanged (scan by service UUID plus `retrieveConnectedPeripherals`).
- **Identity:** HELLO's `installationId` is the identity everywhere. Name matching is only a first-contact fallback (Mac), as today. Advertisements stay anonymous: service UUID only, no name. macOS drops the local name anyway (Apple forum 711191).

### D4. Setup over LE (replaces SDP plus the RFCOMM setup exchange)

New record types on the existing stream, with no new characteristic:

| Type | Name | Payload |
|---|---|---|
| 0x06 | `SETUP_REQ` | the `BtHandoverSync` JSON `{version, revision, origin, enabled}`, ≤ 1024 B |
| 0x07 | `SETUP_ACK` | the receiver's winning state, same JSON |

Rules:
- The **merge logic stays unchanged** (`docs/bt-handover-sync.md`): revision, then removal before creation, then origin. Only the transport changes. The two-exchange handshake the Mac's `SetupAuthorizer` runs today maps one to one onto `SETUP_REQ` → `SETUP_ACK`, then `SETUP_REQ` (echo) → `SETUP_ACK`.
- `SETUP_*` and `HELLO` are the only records accepted from a **bonded but unauthorized** central. Everything else stays refused until setup has completed. Bonding is enforced because the characteristics require encryption, and the LE keys only exist for a bonded device.
  - Change on the Android server: today a central that isn't authorized is refused when it subscribes (`GATT_INSUFFICIENT_AUTHORIZATION`). Instead, let any bonded central subscribe and complete HELLO, then gate the record types.
- **User confirmation stays two-sided:** the user adds the computer on the phone, and confirms "Add *phone name*?" on the computer. Trust is bound to the `installationId`, so a new installation with the same name prompts again.
- **"Add peer…" on Mac and Windows** lists the devices that advertise the service and answered HELLO, instead of running SDP. This removes the stale-SDP problem for good.
- Once every supported client speaks `SETUP_*`, delete `PeerDiscovery`'s SDP path, `ServiceLookup.swift`, `SetupAuthorizer.swift`, and the RFCOMM server in `BtHandoverSync.kt`.

### D5. The remaining Android channels

- **#6 case evidence:** carry it as V2 frames in `V2` records. It has its own frame kinds, so old peers that don't understand them ignore them (the reason it had a separate RFCOMM endpoint).
- **#5 relay signalling:** add `0x08 FRAG`, with payload `msgId(u16) | index(u16) | count(u16) | bytes`. Reassemble up to 128 KiB. Drop an incomplete message after 10 s.
  - First check whether frames above 4 KB really occur. If not, cap them at 4 KB and skip `FRAG`.
- **#7 trace upload:** debug only, and a poor fit for LE (256 MB). Move it to the Wi-Fi relay path or document `adb pull`.

### D6. Security

- Characteristics keep requiring encryption. The keys come from the classic bond through cross-transport key derivation (CTKD), as verified between Android and macOS.
- **Gate S1 (Windows):** whether a classic-bonded Windows PC derives LE keys without a prompt is **unverified**. Microsoft documents CTKD from LE to BR/EDR, not the other direction.
  - Fallback 1: call `DeviceInformation.Pairing.PairAsync(ProtectionLevel.Encryption)` once during setup, and accept a one-time prompt.
  - Fallback 2: unencrypted characteristics with application-layer authentication (a signed HELLO), as bitchat does with Noise. This is a bigger change: avoid it unless S1 and Fallback 1 both fail.
- **Gate S2 (Mac peripheral ← Windows central):** the same question in the other direction, with `CBAttributePermissions.readEncryptionRequired`.

## Implementation plan

Each phase ships on its own and keeps RFCOMM as the fallback until its pairs pass the test matrix. RFCOMM code is deleted in phase 5 only.

### Phase 0: hardware spikes (go/no-go gates, about one day)

| Spike | Setup | Pass |
|---|---|---|
| S1 | A Windows console tool (C++/WinRT) connects as central to the Pixel's existing GATT server, subscribes to TX and writes HELLO | Encrypted access with no prompt, or a single prompt after `PairAsync` |
| S2 | A Mac `CBPeripheralManager` test app advertising the service; the S1 tool connects to it | Same as S1 |
| S3 | An Android test build: the Pixel connects directly to the tablet's GATT server by classic address, without scanning | Connects within 25 s, including after Bluetooth off/on on either side |

If S1 fails, Windows uses Fallback 1. If S2 fails, the Mac ↔ Windows link waits, and the pair keeps working uncoordinated as today. If S3 fails, the Android central always scans first.

**Results (2026-11-22):**
- **S3 passed.** The Pixel connected to the Xiaomi tablet's GATT server with `connectGatt(classicAddress, TRANSPORT_LE)` and no scan: connected in 5.3 s, MTU 517, service found at 7.5 s, encrypted subscribe succeeded with no prompt, and the tablet's HELLO arrived.
  - After the tablet's Bluetooth was turned off and on, the encrypted subscribe succeeded again within 2.1 s. The link was already back by then, apparently re-opened by the stack for an earlier client, so the fully cold path still needs measuring in T4.
  - Closing a client and reconnecting at once produced duplicate callbacks from two clients (status 201, busy). The per-link ids in 2a are required, not optional.
- **S2 failed (Windows → Mac peripheral).** Windows, using Bleak 0.22.3 over WinRT, connected in 5–9 s with MTU 527 and found the service with an uncached discovery. The encrypted read failed with `0x05 Insufficient Authentication` and no prompt, and the encrypted write without response was dropped silently. So Windows gets no LE keys from its classic bond with the Mac.
- **S1 failed, and differently (Windows → phones).**
  - Pixel: the LE link came up and was **encrypted** (the Pixel logged AES with a 16-byte key, no prompt), so the keys exist. But WinRT never completed GATT discovery: `Unreachable` three times, then Bleak hung in `connect()` waiting for the session, with the LE link up for minutes. Closing the EveryPods Windows app, which drops the classic link, did not help.
  - Xiaomi tablet (seen under a private address): `FromBluetoothAddressAsync` failed immediately with `E_UNEXPECTED`.
  - Windows' cached GATT database for the phones was stale: a cached discovery showed no EveryPods service.
- Windows reported the Pixel by its classic (identity) address and the tablet by a private one.
- **Fallback 1 as tried (`pair(Encryption)` while the classic bond exists) is a no-op.** Bleak returned True in 16 ms ("already paired" through the classic bond), and the Mac read still failed with `0x05`. For the Pixel, `connect()` hung again before pairing. A real LE pairing first needs the classic pairing removed on Windows.

- **Fallback 1 with LE-only pairing (Mac's classic pairing removed on Windows) failed on the Mac side.** macOS denied the PC's LE pairing request: bluetoothd logged `Denying pairing request … as there's no one to handle the request` (SMP status 4815). It did the same with System Settings → Bluetooth open, and no prompt appeared. CoreBluetooth gives a peripheral app no public way to accept an incoming LE pairing.

**Decision (2026-11-22, accepted by the user):**
- **LE for phone ↔ Mac** (done), **phone ↔ phone** (S3 passed) and **setup** (D4).
- **Windows keeps RFCOMM to the phones.** WinRT can't use LE with a phone it is classic-bonded to: the link encrypts, but GATT discovery never completes. Replacing the classic bond with an LE bond would break the RFCOMM link and is untested.
- **Mac ↔ Windows stays without a link.** Handovers already work uncoordinated. If coordination is wanted later, the only path left is D6 Fallback 2: unencrypted characteristics with an application-layer signed HELLO.
- Phase 5 then deletes RFCOMM on Android only for phone ↔ phone and setup. The legacy and V2 servers stay for Windows.

### Phase 1: done

Android GATT server and Mac LE central (#104, #7).

### Phase 2: links for every pair

| Work | Client | Copy from | Size |
|---|---|---|---|
| 2a. `PeerGattClient` (central): request queue with one operation at a time, direct connect then scan, MTU 517, CCCD, write without response, link ids, backoff, `close()` after the disconnect callback, `refresh()` only on service-change symptoms | Android | Nordic `BleManagerHandler`/`ConnectRequest`; bitchat-android `BluetoothGattClientManager` | L |
| 2b. Replace the name election with `installationId` election; dedupe at HELLO; route phone-to-phone through 2a | Android (`CrossDevice`) | peer-ble-transport.md §1; bitchat `BLERedundantLinkPolicy` | M |
| ~~2c~~ Dropped (Windows keeps RFCOMM). Kept for reference: `PeerGattClient` for Windows: MTA worker thread with `.get()` (like `BluetoothRfcommClient`), watcher → `FromBluetoothAddressAsync` → `GetGattServicesForUuidAsync(Uncached)` with retries on `Unreachable` → keep service and characteristic objects as members → `MaxPduSize − 3` → `ValueChanged` before the CCCD write → teardown in Bleak's order | Windows | SimpleBLE `PeripheralWindows.cpp`; Bleak `winrt/client.py` | L |
| ~~2d~~ Dropped. Shared record codec in C++, with the test vectors from the Kotlin and Swift tests | Windows | `PeerGattRecords.kt`/`.swift` | S |
| ~~2e~~ Dropped (no Mac ↔ Windows link). `PeerGattPeripheral` on the Mac: add the service on `.poweredOn`, advertise from `didAdd`, notification queue drained in `peripheralManagerIsReady(toUpdateSubscribers:)`, drop all state on power off, re-add on wake | Mac | bitchat `BLEService+LinkLayerPeripheralRole.swift` | M |
| ~~2f~~ Dropped. Windows `PeerRegistry` on LE, matched by `installationId` | Windows | Mac `PeerRegistry.swift` | S |

### Phase 3: setup over LE

- 3a. `SETUP_REQ`/`SETUP_ACK` in all three codecs, with vectors.
- 3b. Android server: bonded centrals may subscribe; record-type gating; `BtHandoverSync` merge reused behind the new transport.
- 3c. Mac: "Add peer…" from advertising plus HELLO; `SetupAuthorizer` logic over `SETUP_*`.
- ~~3d~~ Dropped: Windows keeps its current setup path, so the Android `BtHandoverSync` RFCOMM server stays for it.
- 3e. Android phone-to-phone add over 2a.

### Phase 4: the remaining channels

- 4a. Case evidence over `V2` records.
- 4b. Relay signalling over `FRAG` (or a 4 KB cap).
- 4c. Trace upload moved off Bluetooth.

Done on `feat/peer-gatt-channels` (2026-11-22). What changed from the plan:
- 4a carries the whole V2 protocol over `V2` records, not only case evidence: since phase 2, phones linked over LE ran no V2, so there were no ownership snapshots and no case evidence between them. Case evidence uses its own `CASE` (0x09) record.
- 4b uses `FRAG` (0x08): WebRTC setup messages can reach 64 KB, so a 4 KB cap was unsafe. Relay signalling uses `RELAY` (0x0A) records with a link id in each.
- 4c: no change. Only Windows sends traces, and Windows keeps RFCOMM.
- Found on the way: GATT chunks must be at most 512 bytes (the attribute limit), not MTU − 3; computers are not sent case evidence (neither implements it).

### Phase 5: delete RFCOMM

- Android: remove the phone ↔ phone RFCOMM client (#3, `PeerConnectionSupervisor`), the case-evidence and relay-signalling RFCOMM endpoints (#5, #6) and the trace receiver (#7) once their LE or Wi-Fi replacements pass. Keep the legacy and V2 servers (#1, #2) and the `BtHandoverSync` server (#4) for Windows. Keep the AirPods' SDP calls.
- Mac: remove `ServiceLookup`, `SetupAuthorizer`, and the SDP path in `PeerDiscovery`.
- Windows: no change. It keeps `BluetoothRfcommClient`, and Android keeps the legacy and V2 RFCOMM servers and the `BtHandoverSync` server for it.
- Version gate: a peer that only speaks RFCOMM can't link any more. Show "update EveryPods on *device*" when HELLO is missing but the bonded device advertises nothing.

### How to run the work with agents

| Work | Model | Why |
|---|---|---|
| Phase 0 spikes, 2b, 3b (protocol and gating decisions) | Opus | Cross-client design decisions, where mistakes are costly |
| 2a, 2c, 2e (ports of the named references, against this spec) | Sonnet | Well specified, and the reference code exists |
| 2d, 2f, 3a, 4c, codec vectors, phase 5 deletions | Haiku or Sonnet | Mechanical work with tests to check it |
| Code review of each phase | Sonnet, plus the user's hardware tests | Cheaper review; the hardware matrix is the real check |

One writer per client at a time. Android and Windows can run in parallel because they touch different repos.

## Test plan

### Automated

- **Codec vectors:** one shared file of hex vectors (every record type, chunk boundaries, oversize, type 0, truncated), run by the Kotlin (Robolectric), Swift (`Tests/run.sh`) and C++ (CTest) tests.
- **Android:** the outbox and client queue (one operation in flight, 133 classified as timeout or error, a stale link id ignored), the election (`installationId` order, equal names), dedupe at HELLO, setup gating (only HELLO and `SETUP_*` before authorization), `FRAG` reassembly and its timeout.
- **Mac and Windows:** the peer matching rules, the setup state machine against a fake transport, and the peripheral notification queue's backpressure.
- The existing suites keep passing (889 Android tests).

### Hardware matrix

Devices: Pixel 10, the second phone, the Xiaomi tablet, the MacBook Air, the Windows PC, AirPods Pro 3.

For every pair below, run each scenario once. A pair passes when all of them pass.

| Scenario | Pass |
|---|---|
| T1 Link comes up after the app starts | ≤ 3 s phone ↔ computer, ≤ 10 s phone ↔ phone |
| T2 Media takeover in each direction | sound on the new holder; the old one pauses; ≤ 2 s for a computer taking from a phone |
| T3 Call on the holder refuses a request, then releases after the call ends | as verified on the Mac today |
| T4 Bluetooth off/on on either side | link back within 10 s, no duplicate links |
| T5 Out of range and back; sleep/wake (Mac, PC); doze (phone, 1 h screen off) | link back within 15 s of returning, or of wake |
| T6 App killed or force-stopped and restarted on either side | link back within 10 s |
| T7 Add a new peer from nothing (bond exists, app freshly installed) | succeeds over LE only, with no SDP in the logs |
| T8 Remove a peer on one side | the other side stops linking; no reconnect loop |
| T9 Absent peer configured (device off) | no delay to the AirPods connect (the original RFCOMM paging problem) |

Pairs: Pixel ↔ tablet, Pixel ↔ second phone, every phone ↔ Mac, every phone ↔ Windows, Mac ↔ Windows. Then a mixed round across all of them, like the one run on 2026-11-22.

#### Results so far (2026-11-22, Pixel 10, Xiaomi tablet, MacBook Air, Windows PC)

| Scenario | Pixel ↔ tablet | Phone ↔ Mac | Phone ↔ Windows |
|---|---|---|---|
| T1/T6 app restart | pass: 2–7 s; 5 s with both restarted | phone restarted: 1.8–2.2 s. Mac app restarted: tablet 4.0 s, Pixel 6.9 s (**over the 3 s target**: macOS takes 0.4–2.8 s to discover and ~4 s to connect) | — |
| T2 media takeover | pass (earlier rounds) | pass (earlier rounds) | pass: Pixel → PC → tablet → PC, sound at each step |
| T4 Bluetooth off/on | pass: 3.8 s after both were on. First run took 11 s, because the RFCOMM fallback dial held up LE setup; fixed by dialling LE-known phones over LE only | Mac toggled: tablet 3.5 s, Pixel 9.1 s | — |
| T5 range, sleep, doze | not run | not run | not run |
| T7 add from nothing | not run | not run | — |
| T8 remove a peer | pass: synced over LE, no redial; re-add on both sides synced over LE | — | — |
| T9 absent peer | pass: AirPods connected 1.7 s after lid open, no classic paging of the absent tablet | — | — |

Also verified over LE: case evidence exchanged between the phones, and a Wi-Fi relay session set up through LE signalling and streaming (after fixing dual Wi-Fi interface selection on the sender).

Phase 5 build (2026-11-22): a handover round Pixel → Mac → tablet → Pixel ran over LE only, with sound at each step. Pixel → PC → Pixel also worked, but the first PC attempt failed on the Windows side: the Pixel released at once, yet all 5 of the PC's connects ended "no link within 6 s" and the AirPods only connected about 30 s later. Tracked in ntsour/Everypods-private#10. It is not caused by the LE work, since Windows still uses RFCOMM.

A phone advertised slowly while its other phone was linked, so a Mac that dropped took 14 s to find it again. A phone now advertises fast while a computer that has linked over LE is missing.

### Soak and regression

- 24 h with every device linked: count link drops, duplicate links and reconnect times from the logs. Pass: no lost handover, and no link down for more than 30 s while both devices are in range.
- Battery: the phone's daily drain with the links idle, compared with the phase-1 build. Pass: within 2%.
- AirPods regression: the time to connect the AirPods, with one or two absent peers configured, is no worse than with no peers.
- Before blaming the code, check the AirPods pairing: a broken pairing record on the Mac once made every build fail. Re-pairing in System Settings fixed it.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Windows doesn't derive LE keys from the classic bond (S1) | medium | One-time `PairAsync`; worst case, application-layer authentication |
| Windows links that don't drop, or `Unreachable` after a bond (Bleak issue 807, Nordic DevZone 48916) | high | Teardown order from Bleak; retries; wait for `Disconnected`; identity by `installationId` |
| Android error 133, OEM quirks (Xiaomi, others) | high | Nordic queue and retry rules; test on all three Android devices; RFCOMM fallback until phase 5 |
| Scan throttling (about 5 starts per 30 s, unverified) | medium | One long-lived filtered scan; no restart loops |
| Mac peripheral reliability (iOS-era reports of advertising not restarting) | low | Re-add and re-advertise on every `.poweredOn` and wake |
| Old app versions after phase 5 | certain | "Update EveryPods" message; ship phase 5 only after all clients are on LE |

## References

- bitchat (macOS/iOS): https://github.com/permissionlesstech/bitchat — `bitchat/Services/BLE/BLEService+LinkLayerPeripheralRole.swift`, `BLERedundantLinkPolicy.swift`, `BLERadioController.swift`
- bitchat-android: https://github.com/permissionlesstech/bitchat-android — `mesh/BluetoothGattServerManager.kt`, `BluetoothGattClientManager.kt`, `BluetoothConnectionTracker.kt`, `PowerManager.kt`
- Nordic Android-BLE-Library: https://github.com/NordicSemiconductor/Android-BLE-Library — `BleManagerHandler.java`, `ConnectRequest.java`
- RxAndroidBle connection FAQ: https://github.com/dariuszseweryn/RxAndroidBle/wiki/FAQ:-Cannot-connect
- SimpleBLE WinRT backend: https://github.com/simpleble/simpleble — `simpleble/src/backends/windows/PeripheralWindows.cpp`, `LocalPeripheralWindows.cpp`
- Bleak WinRT backend: https://github.com/hbldh/bleak/blob/develop/bleak/backends/winrt/client.py; issue 807 (links that don't drop)
- Microsoft GATT client: https://learn.microsoft.com/windows/apps/develop/devices-sensors/gatt-client; GATT server: https://learn.microsoft.com/windows/apps/develop/devices-sensors/gatt-server
- Microsoft pairing and CTKD guidance: https://learn.microsoft.com/windows-hardware/design/accessory-guidelines/bluetooth-accessory-guidelines/bluetooth-accessory-guidelines-pairing-bonding-connecting
- Apple, best practices for a local peripheral: https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral.html; forum threads 711191 (local name dropped), 851067 and 742792 (identifier stability)
- Android: https://developer.android.com/develop/connectivity/bluetooth/find-ble-devices, https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
