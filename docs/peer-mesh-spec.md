# EveryPods peer mesh: one protocol for every device

Status: proposed, 2026-11-21. Nothing here is implemented yet. It replaces the
mix of rules in `peer-protocol-v2.md`, `bt-handover-sync.md`, the legacy
4-byte handover path and the Windows/Mac special cases with one set of rules.

## Principle

**Every EveryPods app is an equal peer.** Android, Windows and Mac run the same
protocol, follow the same rules and expose the same handover settings. A user
should be able to predict what happens from the settings alone, without
knowing which kind of device is involved.

Devices may differ only in *how* they carry out a step (how they connect,
release or detect a call), because their operating systems allow different
things. Those differences are announced as **capabilities** in the protocol
and change timing, never the rules. No code may branch on "is this a phone"
or "is this a computer".

## What changes, in one table

| Topic | Today | Spec |
|---|---|---|
| Who dials | Computers dial phones; phones never dial computers; computers can't link to each other | Every peer listens and dials; one link per pair |
| Protocol | Phones: v2 + legacy. Windows: legacy + part of v2. Mac: legacy | v2 (extended here) on every peer; legacy only for old builds |
| Explicit takeover | Legacy 4-byte packets; Windows connects without waiting; phones wait only for computers | One handshake for all: request, grant, release, connect |
| Who can refuse | Only Windows (call protection, 3 s guard); phones always yield | Same grant rules on every holder, driven by shared settings |
| Case-open (passive) booking | Phones only; Windows only respects bookings | Any peer can book |
| What a peer knows | Windows: "here / somewhere else" | Every peer knows which peer holds the AirPods |
| Settings | Different per app | Same handover settings on every app |

## 1. Links

### 1.1 Endpoint

All peers listen on the **v2 RFCOMM service**
`97ce0b4d-8e2f-4a6d-b6cd-d69d362e75ac` (EPH2 framing, `peer-protocol-v2.md`).
The temporary computer-only service `fc163292-…` (branch
`feat/computer-peer-link`) is dropped in favour of this one.

Peers keep listening on the legacy service `1abbb9a4-…` and the setup service
`de51a5b3-…` only as described in §6 (setup) and §8 (compatibility).

### 1.2 One link per pair

- Every peer dials every configured peer it has no ready link with, with
  backoff (1.5 s doubling to 15 s), and accepts incoming links from configured
  peers.
- If two links come up between the same pair, both ends keep the one with the
  lexicographically smallest `(initiatorId, connectionId)` from HELLO and close
  the other (the existing v2 rule). The Bluetooth address is not used.
- The peer with the smaller `installationId` dials first; the other waits 3 s
  before its first dial, so races are rare.
- A peer that cannot listen (capability `no-listen`, §3) is dialled by the
  others and never expected to accept.

### 1.3 Liveness

HELLO, SNAPSHOT, PING/PONG and their timings are unchanged: PING every 10 s,
close after two missed responses, READY only after a matching PONG and an
accepted snapshot. Every peer, phone or computer, runs the same heartbeat.

## 2. State every peer publishes

SNAPSHOT is unchanged: `headsetId`, `ownership` (`held`/`released`/`unknown`),
`audioUse` (`idle`/`media`/`call`/`unknown`), `lastOwnerId`, `acquisition`.

Each peer works out "who holds the AirPods" from the fresh snapshots of all
peers plus its own state. A peer whose snapshot is stale or missing counts as
`unknown`, never as `released`.

Every peer keeps a table *peer → (ownership, audioUse, freshness)* and shows
the holder's name in its UI ("AirPods: on Pixel 10"), not "on Android" or
"somewhere else".

## 3. Capabilities

HELLO `capabilities` gains these entries. They describe the device, not its
type. Each is optional; a peer omits what it cannot do.

| Capability | Meaning | Android | Windows | Mac |
|---|---|---|---|---|
| `acquire-v2` | Speaks the unified handshake (§4) | yes | yes | yes |
| `force-release` | Can drop its own audio link at will, and confirm when it is down | no (no BLUETOOTH_PRIVILEGED) | yes (KS disconnect, link drop) | yes (IOBluetooth close) |
| `call-detect` | Can tell reliably that it is in a call | yes (telephony + VoIP) | partly (Teams/WASAPI) | partly (per-process audio) |
| `media-detect` | Can tell that it started media playback | yes | yes (GSMTC) | yes |
| `no-listen` | Cannot accept incoming peer links | — | — | — |

Capabilities only change **timing** (§4.4) and what a peer can report. They
never change who wins.

## 4. Taking the AirPods: one handshake

### 4.1 Intents

Every attempt to take the AirPods carries an intent, in priority order:

| Intent | Raised when | Example |
|---|---|---|
| `call` | This device rings or a call starts here | Phone rings; Teams call joined on the PC |
| `manual` | The user explicitly asks this device to take them | Tray / menu-bar "Take AirPods", in-app connect button |
| `media` | Media playback starts on this device | Play in Spotify, YouTube in a browser |
| `passive` | Case opened / buds out, and this device is the one that should reconnect | Case-open last-holder auto-connect |

### 4.2 Messages

The existing ACQUIRE_* messages carry every intent (explicit takeovers
included), so there is one path instead of two:

1. **Requester → every ready peer:** `ACQUIRE_REQUEST` with the new `intent`
   field (§7).
2. **Each peer → requester:** `ACQUIRE_GRANT` or `ACQUIRE_DENY` (reasons as
   today, plus `POLICY` and `CALL_ACTIVE`, §7). A non-holder grants unless it
   is itself mid-acquisition (`BUSY`).
3. **Requester → every granter:** `ACQUIRE_START`; granters answer
   `ACQUIRE_START_ACK`. For non-passive intents the **holder starts releasing
   when it sends START_ACK**.
4. **Holder:** releases the AirPods (§4.4), then publishes a SNAPSHOT with
   `ownership: released`. That snapshot is the release confirmation.
5. **Requester:** connects (§4.4), then publishes `ownership: held`.

The legacy `REQUEST_DISCONNECT` / `AIRPODS_DISCONNECTED` / `AIRPODS_CONNECTED`
packets are no longer used between peers that both speak `acquire-v2`.

### 4.3 Who wins: the grant rules

Only the **holder's** decision matters for whether the AirPods move. Every
holder applies the same rules, in this order:

1. `call` intent: always granted. A call anywhere beats anything except
   a call already active on the holder; then rule 2 applies.
2. Holder is in an active call (`audioUse: call`) and the intent is not `call`:
   deny `CALL_ACTIVE`. If both have calls, the newer call wins (the user just
   answered it).
3. Holder took the AirPods less than **3 s** ago and the intent is `media` or
   `passive`: deny `BUSY` (ping-pong guard). `manual` and `call` are never
   blocked by this.
4. The holder's **"Let the AirPods move to another device when"** setting
   (§5) does not allow the holder's current state (idle / playing media / on
   a call): deny `POLICY`. `manual` overrides this rule.
5. `passive` intent while the holder is `held` (any state): deny `HELD`.
   Passive reconnect never takes the AirPods from a device that has them.
6. Otherwise: grant.

On the **requester** side, each intent is raised only if the matching
"Take the AirPods when" setting (§5) is on. `manual` is always allowed.

Ties: two requesters at once with the same intent → the holder grants the
first request it receives and denies the other `BUSY`; between two non-holders
the existing v2 membership/digest rules already prevent double grants.

### 4.4 How each side acts (where capabilities matter)

**Holder releasing**
- With `force-release`: drop the audio link (let it close on its own first,
  for up to 4.5 s, then force it), then publish `released`.
- Without `force-release`: stop sending audio (pause local playback for
  `media` intents) and publish `released` at once: the device cannot do more,
  and the AirPods switch when the requester connects.

**Requester connecting**
- Wait for the holder's `released` snapshot, up to a deadline set by the
  holder's capabilities: 6 s if it has `force-release`, 0 s if not.
- Then connect, retrying up to 5 times back to back.
- If there is no holder (all fresh snapshots `released`), connect at once.
- If the holder's state is `unknown` (link down), connect at once for `call`
  and `manual`, after 2 s for `media`, and not at all for `passive`
  (`reconnect-protocol-design.md`, offline rule).

These numbers come from measurements on 2026-11-21 (PC link closes in
3.4–3.6 s; phone pages that land during teardown stall for 30 s).

### 4.5 After a handover

- The new holder publishes `held` with `audioUse`.
- The old holder, if it has the setting on, pauses or mutes what is still
  playing locally (Windows "Pause PC audio when the AirPods leave" generalises
  to every peer, §5).
- Any peer that sees the AirPods appear on itself outside a handshake
  (the AirPods' own switching, manual Bluetooth connect) publishes `held`
  immediately; peers treat that as a completed handover.

## 5. Settings: the same on every app

Every app shows the same handover settings, with the same names, defaults and
meaning, and applies them through the rules above. Settings stay local to
each device (as `bt-handover-sync.md` already specifies); they are not
synchronised.

| Setting | Meaning | Default | Android today | Windows today | Mac today |
|---|---|---|---|---|---|
| **Handover with other devices** | Master switch: take part in the mesh | on | `cross_device_enabled` | always on | always on |
| **Take the AirPods when this device:** | | | | | |
| … receives a call | Raise `call` intent on ring / call start | on | `takeover_when_ringing_call` | Teams/WASAPI trigger (no switch) | call detection (no switch) |
| … starts playing media | Raise `media` intent | on | `takeover_when_media_start` | always (no switch) | `autoTake` |
| **Let the AirPods move to another device when this device is:** | Holder-side grant policy (§4.3 rule 4) | | | | |
| … not using them (idle) | | on | `takeover_when_idle` (UI only, not wired) | always | always |
| … playing media | | on | `takeover_when_music` (UI only) | always | always |
| … on a call | | off | `takeover_when_call` (UI only) | never (call veto) | never (call hold-off) |
| **Auto-connect on case open (last device)** | Raise `passive` intent if this device held them last | on | `lid_open_last_holder_autoconnect` | none | none |
| **Pause audio here when the AirPods leave** | §4.5 | on | none (Android pauses via the OS) | `pauseOnRelease` | Accessibility media pause |
| **Make the AirPods the audio output when they connect** | Route audio to them on connect | on | OS does it | `autoRoute` | OS does it |
| **Start with the system** | Launch at login | on | n/a (service) | Start with Windows | Launch at login |

Notes:
- "On a call" defaults to **off**: today every client except Android
  protects a call, and Android's phone-call path already does in practice.
  Making it a visible setting replaces the hidden Windows call veto and the
  Mac call hold-off.
- `takeover_when_disconnected` is removed: "AirPods not connected anywhere"
  always allows a takeover.
- Settings that only make sense on one OS (Android ear detection, Windows
  audio routing) stay per-app; they are not part of the handover rules.

## 6. Setup and membership

- Adding a peer uses the existing setup exchange (`bt-handover-sync.md`,
  service `de51a5b3-…`) between **any** two peers. Every app serves it and
  can start it, so a PC can add a Mac, a Mac a PC, a phone a PC, and so on.
- Adding on one device creates the entry on the other; removing on either
  removes both (as today between phones).
- Bluetooth pairing remains the trust boundary; an unpaired device cannot
  set up or link.
- Each app's UI says "Add device", lists devices by name with a type icon,
  and shows the same status for each: linked / not linked / holds the AirPods.

## 7. Wire changes to v2

All additions are gated on the `acquire-v2` capability; peers without it get
today's messages unchanged (v2 objects reject unknown fields).

- `ACQUIRE_REQUEST`: add `"intent": "call|manual|media|passive"`. Missing is
  only legal from peers without `acquire-v2` and means `passive`.
- `ACQUIRE_DENY.reason`: add `POLICY` and `CALL_ACTIVE`.
- Grant validity stays 5 s; for non-passive intents the holder's release
  starts at START_ACK, so the grant cannot expire mid-release.
- No new message types.

## 8. Compatibility

- A peer without `acquire-v2` (older Android builds, today's Windows/Mac
  clients) keeps the legacy path: requester sends `REQUEST_DISCONNECT`, holder
  releases and replies `AIRPODS_DISCONNECTED`. New peers apply the same grant
  rules (§4.3) to a legacy request, treating it as `media`, and use the same
  release confirmation timing.
- The phone-only special cases are removed once every client speaks
  `acquire-v2`: `ComputerReleaseGate` (wait only for computers), "never dial a
  computer peer" (#96), `WINDOWS_AUDIO_ACTIVE/IDLE`.

## 9. Work per client

**Android**
- Listen and dial every peer, computers included (undo #96 behind
  `acquire-v2`).
- Route explicit takeovers (media start, ringing, manual) through
  `PassiveAcquisitionCoordinator` with an intent, instead of the legacy
  packets; rename it accordingly.
- Apply the grant rules (§4.3) and wire the "Let the AirPods move when"
  settings, which are UI-only today.
- Replace `ComputerReleaseGate` with the capability-based wait (§4.4).
- Show the holder's name.

**Windows**
- Listen on the v2 service; accept and dial phones and computers (fold
  `feat/computer-peer-link` into the v2 mesh instead of a separate service).
- Replace the legacy handover in `HandoverController` with the handshake;
  become a full requester (book for `passive`, `media`, `call`, `manual`).
- Replace the hard-coded call veto and 3 s guard with the shared grant rules
  and settings; add the missing settings to the tray.
- Serve and start the setup exchange with any peer.

**Mac**
- Implement v2 (EPH2, HELLO/SNAPSHOT/PING, ACQUIRE_*) with the same rules;
  listen and dial; multiple peers instead of one phone.
- Same settings as above in the menu bar.

**Shared**
- One conformance test suite: recorded message sequences and expected
  decisions for each rule in §4.3, run by all three clients (Kotlin unit
  tests, Windows `peer_v2_tests`, a Swift test target).

## 10. Order of work

1. Agree this spec.
2. Wire changes (§7) and grant rules (§4.3) on Android, with tests, keeping
   the legacy path for old peers.
3. Windows: v2 listener, handshake, settings. Test Windows ↔ Android, then
   Windows ↔ Windows.
4. Mac: v2 client to the same spec. Test against both.
5. Remove the phone-only special cases (§8) once all three ship `acquire-v2`.

## Open questions

1. Should "Let the AirPods move when this device is playing media" default to
   on (today's "requester always wins") or off (protects what you are
   listening to)? This spec keeps today's behaviour: on.
2. Should settings optionally sync across devices, so the user sets them once?
   This spec keeps them local, like today.
3. When the holder is unreachable but may still hold the AirPods, `media`
   waits 2 s and then connects anyway. Is that the right trade-off between
   responsiveness and fighting an unseen holder?
