# Playback Hold (Android)

Hold the user's media while the AirPods connect, so audio never plays through the
phone speaker during a handover and starts **once**, on the AirPods.

Status: in testing (`feat/android-playback-hold`). Behind a setting, off by default.

## Problem

Today a music handover looks like this:

1. The user presses play. The app starts playing **on the speaker**.
2. `MediaController` detects playback (`AudioPlaybackCallback` / MediaSession) and calls
   `AirPodsService.takeOver("music")`.
3. `takeOver` sends `REQUEST_DISCONNECT` to peers and calls `connectAudio()`, retrying at
   1 s / 2.5 s / 4.5 s.
4. 2–4 s later (6–12 s from the case) the A2DP route lands. The speaker played all along.
5. Many apps (Pocket Casts, …) pause on the output-device change. EveryPods sends
   `sendPlay(force=true)` and `RouteLandPoller` keeps replaying for 15 s. The user hears a
   pause → play stutter.

## Idea

Once a music takeover is about to connect, EveryPods requests **transient audio focus**.
Well-behaved media apps (anything on Media3/ExoPlayer, Spotify, YouTube, Pocket Casts)
pause themselves on `AUDIOFOCUS_LOSS_TRANSIENT` and resume themselves on `AUDIOFOCUS_GAIN`.
So:

- **Hold:** request `AUDIOFOCUS_GAIN_TRANSIENT` → the app pauses within ~100 ms.
- **Release:** when media is really routed to the AirPods, abandon focus → the app
  resumes on the AirPods. The app was paused during the route change, so it has nothing
  to auto-pause, and there's no stutter.

The detection delay can't be removed without root, so a short speaker blip (~100–500 ms)
remains. To hide most of it, `STREAM_MUSIC` is muted the instant the hold starts and
unmuted as soon as the app has actually paused (never later than 1.5 s, and always
before release).

## Lifecycle

```
          takeOver("music") passes all gates, about to connectAudio()
                                 │
                                 ▼
 IDLE ──► HOLDING ── phases: REQUESTING_RELEASE → CONNECTING → ROUTING
             │   │
             │   └─ media verified on AirPods ──► CONNECTED ──► release focus ──► IDLE
             │                                      (app resumes; safety net replays once)
             │
             ├─ timeout (10 s per attempt) ──► FAILED (still holding, still paused)
             │                                   ├─ Retry          → HOLDING (attempt+1)
             │                                   ├─ Play on phone  → release focus → IDLE (speaker)
             │                                   └─ no choice 12 s → stay paused → IDLE
             │
             └─ cancel (user presses play in the app = focus taken back,
                        AirPods taken by a peer, service destroyed) ──► IDLE
```

### Phase signals

| Phase | Entered when |
|---|---|
| `REQUESTING_RELEASE` | Hold starts and a peer is connected (`REQUEST_DISCONNECT` sent) |
| `CONNECTING` | Hold starts with no peer, or `connectAudio()` issued / A2DP `STATE_CONNECTING` for our MAC |
| `ROUTING` | A2DP profile `STATE_CONNECTED` for our MAC, or `onAudioDevicesAdded` for our MAC |
| `CONNECTED` | `AudioManager.getAudioDevicesForAttributes(USAGE_MEDIA)` contains our A2DP MAC (polled every 150 ms after `ROUTING`, up to 3 s, then accepted anyway) |
| `FAILED` | Attempt timeout without `CONNECTED` |

`getAudioDevicesForAttributes` is public from API 33 (`minSdk` is 33). "Device added" alone
isn't enough: on Xiaomi the stream goes live seconds later.

### Release and safety net

On `CONNECTED`: unmute (if still muted), abandon focus, call `MediaController.markSelfAction()`.
800 ms later, if `isMusicActive` is still false, call `sendPlay(force = true)` **once**. This
covers apps that don't resume after a long transient loss. The old `RouteLandPoller` path is
skipped while a hold is active, so play isn't sent twice.

### Failure and choices

The hold stays in place after a failure, so nothing blasts out of the speaker. The overlay
shows two choices:

- **Retry:** `takeOver("music")` again, with the hold kept and a new 10 s timer.
- **Play on phone:** cancel the pending takeover and release focus. The app resumes on
  the speaker.

If the user doesn't choose within 12 s, send a PAUSE media key directly, then abandon focus
250 ms later. The app stays paused (Media3 clears `playWhenReady`) and doesn't resume on the
speaker. `MediaController.sendPause()` can't be used here, because it only fires while music
is active, and during a hold it isn't.

### Fit with the takeover flow on `main`

- The hold starts in `takeOver("music")` after `REQUEST_DISCONNECT` is sent and **before** any
  computer-holder release wait (`awaitComputerRelease`), so the speaker stays quiet for
  that phase too.
- `MediaController.yieldPlaybackToPeer()` (a peer's newer intent) ends the hold and leaves
  the media paused, like losing ownership.
- The release safety net replays locally (`sendPlay(force = true, routeToRelay = false)`),
  never through the relay.
- `restartRouteLandPoller(mac)` is skipped while a hold is active and for 3 s after it ends.

### Small rules added during implementation

- If focus isn't granted (for example during a call), there is no hold and behaviour is as
  today.
- After the user takes playback back (`AUDIOFOCUS_LOSS`), no new hold starts for 5 s. This
  stops the app's own resume from re-triggering a hold.
- `CONNECTED` stays published for 1.5 s after release so the overlay can show it.
  `isActive` is already false by then.
- If another island is open when a hold starts, the hold's island replaces it. Otherwise
  the hold would have no UI.

There is no refusal signal from peers today (`COURTESY_DENY` is ignored), so failure is
timeout-only.

### Cancel

- **User presses play in the app during the hold:** the app requests focus and we get
  `AUDIOFOCUS_LOSS`. End the hold (unmute, drop the overlay's hold state). Connection
  continues as today.
- **AirPods taken by a peer** (the places that call `cancelPendingMusicTakeover()` in
  `AirPodsService`): end the hold and stay paused.
- **Service destroyed:** abandon focus and unmute.

## Mute safety

- Only mute if `STREAM_MUSIC` isn't already muted by the user.
- Persist `playback_hold_muted=true` in SharedPreferences while muted. On service start, if
  it's set, unmute and clear it (crash recovery).
- Hard cap: unmute 1.5 s after the hold starts, whatever happens.
- Use `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE/UNMUTE, 0)`, flag 0, so no volume UI.

## Feedback to the user (overlay)

The hold drives the overlay the user already chose in *Pop-up Animations*:

| Preference | Hold UI |
|---|---|
| Dynamic Island (`show_island_popup`, default on) | Island in a new `CONNECTING` look: the battery ring becomes a progress ring and the text shows the phase. No auto-close while holding. `CONNECTED` turns into the existing *Taking over* look and auto-closes as usual. `FAILED` expands to show **Retry** / **Play on phone**. |
| Bottom sheet (`show_bottom_sheet_popup`) | Same states in the bottom-sheet popup: a status line with a linear progress bar, and Retry / Play on phone on failure. |
| Both on | Island only (it's the lighter surface). |
| Neither on, or no overlay permission | No UI while connecting. On `FAILED`, a high-priority notification with Retry / Play on phone. |

Phase text: "Asking {peer} to let go…", "Connecting to {name}…", "Switching audio…",
"Connected", "Couldn't connect to {name}".

Progress is time-based within each phase band (0–30 % / 30–70 % / 70–95 %, 100 % when
`CONNECTED`), so it always moves. The fraction comes from `HoldStatus`.

## API (shared contract)

`utils/PlaybackHold.kt`, which the logic owns:

```kotlin
enum class HoldPhase { REQUESTING_RELEASE, CONNECTING, ROUTING, CONNECTED, FAILED }
data class HoldStatus(
    val phase: HoldPhase,
    val deviceName: String,
    val peerName: String?,     // for REQUESTING_RELEASE text
    val attempt: Int,          // 1-based
    val phaseStartedAtMs: Long,
    val attemptStartedAtMs: Long,
    val attemptTimeoutMs: Long,
)
object PlaybackHold {
    val status: StateFlow<HoldStatus?>   // null = no hold active
    fun retry()
    fun playOnPhone()
    fun dismissStayPaused()
    fun progressFraction(s: HoldStatus, nowMs: Long): Float
}
```

`presentation/overlays/HoldOverlayController.kt`, which the UI owns: collects
`PlaybackHold.status` and drives the island, popup or notification according to the
preferences above. `AirPodsService` starts and stops it with the service.

## Setting

`hold_playback_until_connected` (Boolean, default `false`), next to "Take over when media
starts". Label: "Hold playback until connected". Description: "Pause media while your
AirPods connect, so nothing plays from the phone speaker." The hold also requires
`takeoverWhenMediaStart`.

## Known limits

- A ~100–500 ms detection blip remains, mostly hidden by the mute.
- Apps that ignore audio focus won't pause. They behave as today, and the mute still hides
  the first 1.5 s.
- Some apps resume on focus gain even if the user paused them in another way during the
  hold. This is rare, because the app's own pause button is the only control and it's
  already paused.
- Android 15+ only grants focus to foreground apps or foreground services. EveryPods runs a
  connected-device foreground service.

## Test matrix

Phones: Pixel (AOSP), Xiaomi (HyperOS). Apps: Spotify, Pocket Casts, YouTube, YT Music.
Scenarios: warm handover from the peer phone; cold connect from the case; peer out of
range (→ FAILED → Retry; → Play on phone; → ignore); press play again in the app during
the hold; setting off (no behaviour change).

## Calls: handover progress only

Calls are never held, muted or paused. When `takeOver("call")` is about to connect (a phone
call ringing or answered, or a VoIP call starting: the existing call triggers), `PlaybackHold.startCall()`
publishes the same `HoldStatus` with `kind = CALL`, and the overlay shows handover progress.

- **Surface:** always the **bottom-sheet popup**, never the island. At the top, the island
  would cover the incoming-call banner and its Answer / Decline buttons. It's shown when
  either pop-up preference is on and the overlay permission is granted. Otherwise only
  failures are shown, as a notification.
- **Phases:** "Asking … to let go" → "Moving your call to {name}…" → "Switching call audio…".
  A2DP events only move the phase to CONNECTING. The headset (HFP) profile connecting moves
  it to ROUTING. `CONNECTED` comes when HFP is connected (in-band ring) or the AirPods are
  `AudioManager.communicationDevice`.
- **Failure (10 s):** "Couldn't connect to {name}" with **Retry** (`takeOver("call")` again)
  and **Use phone** (close the popup; the call stays on the phone). With no choice in 12 s,
  the popup just closes.
- **Ends quietly** when the call ends (cellular `CALL_STATE_IDLE`, or VoIP leaving
  `MODE_IN_COMMUNICATION`) or the AirPods are lost to a peer.
- **A call during a media hold:** the media hold ends with the media left paused, and the
  call status takes over.
- **Lock screen:** a full-screen incoming call on a locked phone may hide the overlay.
  Still to verify on the Pixel and the Xiaomi.
