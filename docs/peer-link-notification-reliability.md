# Phone-to-phone links and notification updates

## Diagnosis and design

The repeated tests showed a healthy v2 reservation exchange but recurring legacy
RFCOMM heartbeat expiry. The legacy parser assumed each InputStream.read was one
packet: an announcement plus a status request in a single read was ignored, as
were split control packets. The watchdog used a peer-global timestamp that could
predate the current socket and checked it immediately after sending a ping.

Use a connection-owned decoder and heartbeat clock on each legacy socket. Decode
fixed four-byte controls across arbitrary chunk boundaries; use known AACP lengths
for the relay formats already supported by the receiver, and keep opaque legacy
payload compatibility. Serialize server writes. Each new connection gets its own
response baseline; only actual status responses on that socket refresh it. Genuine
unresponsive links still time out; a fresh v2 snapshot must not mask a broken
legacy control channel. V2 nonce validation and ownership authority are unchanged.

Notification floods originate in an unconditional status update per AACP read,
followed by notify(2) and cancel(1) even when all visible content is identical.
Remove that packet-level refresh: battery/state/name/settings callbacks already
carry the actual presentation changes. Deduplicate immutable presentation state,
coalesce latest changes to at most one status operation per 750 ms, and avoid
repeated foreground cancellation. Keep first foreground promotion immediate and
refresh its notification only when presentation changes. Preserve localization,
actions, limited Bluetooth mode, and final disconnect cleanup. Stop pending work
when the service is destroyed.

## Implementation plan

1. Add pure buffered legacy decoding and per-socket liveness classes plus tests.
2. Use them in both server and client readers/watchdogs and serialize server writes.
3. Add a pure latest-state notification publisher with deterministic scheduler tests.
4. Wire semantic state publishing into the service; remove per-packet refresh and
   duplicate cancels. Add sparse on-device publication/link lifecycle diagnostics.
5. Run full unit tests, build, lint, localization and diff checks.
6. Install the same signed APK on both reachable devices, preserving data/history.
7. Observe both sides for at least three former failure periods, including an
   active audio/sensor interval when possible. Verify no repeating timeout loop,
   no notification rate-limit warnings, current snapshots, and state cleanup.
   Record the exact on-device coverage and remaining physical-test limits.

## Validation matrix

- Every split point of concatenated legacy status/request/control messages; multiple
  controls in one read, relay data plus controls, and partial reads across sockets.
- New socket ignores stale timestamps from an older connection; timely status
  responses sustain a multi-minute session; absent responses still cause expiry.
- v2 heartbeat wrong nonce still fails; reservation regression suite remains green.
- Thousands of identical notification requests create one operation. Rapid varying
  states produce bounded updates and eventually publish the latest value.
- Pending updates reverted to the displayed state are cancelled; hide/post flaps,
  locale/action changes and service destruction preserve correct final state.
- On-device: peer uptime/snapshots and native audio state plus journal publication
  counts and Android notification rate-limit messages, with per-device timestamps.

## Handover regression exposed by live validation

The first live notification-retake test revealed a separate takeover race.
Pixel's retained audio-system history shows its EveryPods MediaPlayer starting the
handover animation at 17:47:34 as USAGE_MEDIA / CONTENT_TYPE_MOVIE. The route-loss
callback rearmed media detection, while that animation was still active; Pixel's
music takeover stamp corresponds to approximately 17:47:36.646. Pocket Casts has
no fresh start in that interval and only resumes later after automatic Play.
The initiating app logcat callback was evicted, so this trigger is reconstructed
from system player events, the intent timestamp and the classifier implementation.
Xiaomi's retained log also directly shows an old 4.5-second connect retry and
route-land Play retries continuing after a peer-requested yield.

Decorative VideoViews now use non-media sonification attributes, request no audio
focus, and are muted on preparation. Yielding force-pauses old local playback,
cancels pending route recovery/verification, and baselines continuing playback so
route loss does not invent a new user Play. A generation fence invalidates delayed
connect/retry/disconnect work; a newer genuine local intent can supersede an old
yield. Tests cover classification, cancelled replay, fresh user resume, stale
callbacks and stale yield cancellation. Persistent telemetry now records takeover
intent/decision/replay/yield events so a future source callback is not lost with
logcat. Call-hold vetoes remain before yielding.

## Final validation — 29 October 2026

The same signed debug APK was installed over both apps with data preserved.
All 637 unit tests passed; debug assembly, lint, localization checks and diff
checks passed. Hardware evidence is saved under
`tmp/link-notification-validation-2026-10-29/round-trip/`.

The user switched Xiaomi → Pixel → Xiaomi. Pixel recorded a music intent at
18:03:46.058 and A2DP connected at 18:03:49.554. Xiaomi's return request was
explicitly attributed to the Pocket Casts media session at 18:04:02.285. Pixel
yielded at 18:04:03.941, cancelled old playback recovery and force-paused at
18:04:03.962. Xiaomi A2DP connected at 18:04:06.398; Pixel disconnected and
cleared its connected notification at 18:04:06.987. Through the final snapshots
at 18:06:19–21, Xiaomi remained HELD/MEDIA and Pixel RELEASED/IDLE. Pixel emitted
no later takeover intent or route-recovery Play. Its later lid-open observation
correctly skipped acquisition because Xiaomi held the AirPods.

Android audio-system history independently confirms every new EveryPods animation
player on both devices received USAGE_ASSISTANCE_SONIFICATION and
CONTENT_TYPE_SONIFICATION before starting. Thus this test exercised the actual
handover animation and yield paths, rather than only steady ownership.

From the final app startup around 18:00:33–38 through 18:06:19–21, peer snapshots
stayed current and there was no recurring heartbeat expiry/backoff. The sole early
legacy close occurred while installing/restarting Pixel; the reopened socket
remained alive for over five minutes. Status notification operations numbered
three on Xiaomi and six on Pixel, with the minimum Pixel spacing 752 ms. Connected
notifications posted and cleared in both directions. Retained NotificationService
logs contained no EveryPods rate-limit/shedding warning in this interval.

This validates this round trip and several minutes of stable links, not an
exhaustive radio-loss, process-death, call, or overnight endurance matrix. Xiaomi's
limited AACP support remains distinct from its successful native audio handover.
