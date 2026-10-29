# Case opening and last-holder recovery

Case and automatic-connection events are recorded directly in a separate journal,
independent of Android logcat and the five-minute live diagnostics preview.

- Location: app-private `files/case-connection-history/case-<epoch-hour>.log`.
- Retention: at least 48 hours; only whole older hourly segments expire. There is
  no line-count or byte-count eviction that could erase a busy day early.
- Writes run sequentially on a dedicated background thread. Each event closes its
  file; history survives normal process death, reboot, and in-place app updates.
  Like any app data, uninstalling/clearing storage removes it. Storage failures
  appear in logcat and the exported report's `journalWriteError` field. Unwritten
  queued events can be lost on abrupt process termination.
- Every line has wall-clock and elapsed-realtime milliseconds. Process-start
  events distinguish monotonic clock resets; a cycle number groups lid events.
- Records include lid open/close/unknown, ear and charging changes, scanner
  lifecycle/failures, feature/holder settings at lid open, lease claims/releases,
  peer snapshots, passive reservation requests/denials/grants, retry decisions,
  A2DP/headset state transitions, ACL disconnect reason when Android supplies it,
  and AACP socket attempt/connection/ack/closure/failure. BLE observations only
  record state changes and occasional heartbeats to avoid per-packet noise.
- Addresses/UUIDs/IP addresses are redacted before journal storage and export.
  The log does not contain raw Bluetooth packets or media/notification content.

Export using **Debug → Report lid-open connect failure**. Export runs off the UI
thread and includes the full retained journal plus the current live snapshot.
For an installed debuggable build, ADB can read the same history with
`adb shell run-as io.automated.ventures.everypods ls files/case-connection-history`.

## Recovery behavior

Only a completed A2DP connection claims ownership. `STATE_CONNECTING` does not
announce ownership, replace the last holder, or open the AACP socket prematurely.
A short passive connection failure applies a contention cooldown but preserves
last-holder memory; confirmed peer ownership or an explicit yield still releases
it. Shared acquisition continues to require the existing peer reservation, with
an offline exception only for the remembered last holder when no peer is reachable.

Lid opening starts a bounded 15-second opportunity to recover, initially after
400 ms. Closing the case with both buds charging cancels it. Closing an empty case
after removing the buds preserves the remaining opportunity. Ear insertion can
start the same bounded recovery for the remembered holder if a lid event was
missed. Delayed profile callbacks recheck cancellation, cooldowns and peer
ownership before issuing a passive connection command. Debounce uses monotonic
time throughout. Shared recovery after A2DP flux starts a new reservation round.

Unit tests cover simulated busy-day retention and restart, the 48-hour expiration
boundary, clock rollback, charging versus empty-case closure, and actual A2DP
state values for ownership persistence. These are not a substitute for live
Pixel/peer tests across lid cycles, simultaneous opens, an offline peer, Bluetooth
off/on, and an unexpected disconnect while wearing the buds.
