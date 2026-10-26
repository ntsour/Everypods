# Head tracking: signals and command lifecycle audit

26 October 2026. Scope: head-motion input, wear/connection evidence, stream demand, commands, recovery, gesture recognition, and action dispatch. Media playback commands are unchanged.

## Observations and diagnosis

The Pixel had Nod=Previous and Shake=Next saved, with the master enabled. After the app was restarted for a diagnostic build, live logs showed both gestures being recognized and dispatched. The user identified connection/reconnection as the trigger for the original failure. In the later installed-build trace, Gym Mode was active with both Gym gesture bindings unassigned; normal Next/Previous assignments were correctly suppressed. This is a separate inactive-profile condition, not evidence of lost motion. A localized active-Gym hint and direct Gym-controls link now make that state visible without changing mappings. No pre-restart controller trace exists, so the original incident cannot be attributed to a single gate with certainty.

The code audit found a concrete reconnect race: wear events were discarded until the connection engine published Connected, although a valid report may arrive earlier from the installed socket. The polling controller also cleared early evidence while the engine was handshaking. A second independent gap was treating a start request as sufficient indefinitely, with no recovery when valid motion stopped. Regression tests now cover both failures without requiring an app restart.

## Signal and ownership model

1. BluetoothConnectionManager owns the current AACP socket and its generation. Connection readiness and a local A2DP route are prerequisites for commands/actions. No head-tracking recovery initiates connection takeover.
2. AACP wear reports supply a left/right worn mask for that socket generation. Unknown is distinct from both out. Reports received during handshake are retained, but cannot authorize actions before Connected. New generation/disconnect invalidates old evidence.
3. BLE is not the motion transport. Losing advertisements does not invalidate a healthy AACP stream. A BLE ear-state change may request a fresh AACP wear report; it cannot independently grant wear/ownership or enable gestures.
4. BuddyFrameDecoder continues handling fragmented/coalesced AACP envelopes. HeadMotionProtocol validates the sensor/protobuf layout and preserves all samples in batched commands. HR/unknown sensor reports are not head gestures.
5. Decoding remains off the UI thread; visual calibration, detector updates, stream accounting and dispatch run together on the main controller thread. Reset and calibration no longer race across threads.
6. A stream request is not evidence of reception. Only a current-generation, fresh, decoded sample confirms receiving. Delayed input (over 250 ms), old generations and old control epochs are discarded.

## Findings and changes

| Situation | Required behavior and implementation |
| --- | --- |
| Wear report arrives before Connected | Retain it for the installed generation; start automatically when readiness is published. |
| First wear report is missing | Up to three bounded AACP notification refreshes while an enabled owner needs evidence. Stop polling once authoritative state is known. |
| Remove left or right bud | If the other remains worn, keep demand; clear partial motion, recalibrate visual orientation, and require 300 ms neutral before rearming. A changed worn mask matters even when “at least one worn” remains true. |
| Remove both | Disable detection and stop the stream, including explicit diagnostic/test owners. Stop delivery failures cannot leave action execution enabled. |
| Reinsert either/both | Fresh current-socket wear evidence restores demand automatically; insertion motion cannot complete a previous gesture. |
| Lose BLE temporarily | AACP motion continues. No BLE freshness requirement or cached BLE-positive fallback authorizes gestures. |
| Lose AACP with BLE/audio still present | Disable actions, abandon that generation's requests, invalidate wear/partial motion, and wait for the ordinary connection machinery. No BLE-only gesture execution. |
| Reconnect AACP | New generation requires its own report, including valid early handshake reports; current settings are reapplied automatically. |
| Motion stream stops while AACP remains up | Re-send the selected start command after 2 seconds without valid samples, with subsequent 3/5-second backoff. Three retries maximum; report UNAVAILABLE rather than loop or steal ownership. Valid samples restore the retry budget. |
| Start write fails | A failed Boolean send result does not confirm reception. Recovery remains bounded and observable. |
| Stop write fails | Block actions immediately; retry stop with bounded backoff while the same local connection remains eligible. Successful write clears pending stop; a new generation cannot receive an old stop. |
| Change alternate-packet option | Stop the old service with its old packet variant before starting the newly selected variant. Do not silently choose a different variant. |
| Give AirPods to another device | Stop local interpretation and abandon queued command intent. Do not send a stop/reset to a peer-owned stream. Recheck ownership and generation at the command boundary. |
| Change call/Gym/normal/announcement context | Clear partial motion and advance the input epoch, while retaining any completed gesture's cooldown. Old queued packets cannot finish a command under new assignments. |
| Call starts during a test | Cancel the test and use call mappings; an unassigned call gesture cannot fall through to announcements or media. |
| Background the configuration Activity | Cancel the explicit safe test and release diagnostics. Returning may resume expanded diagnostics, but never silently restarts a test. Ordinary configured gestures remain service-owned. |
| Close/collapse diagnostics | Release only that owner. Keep tracking if an enabled action still requires it. If no owner remains, stop. |
| Malformed/truncated input | Ignore malformed motion envelopes; reject truncated wear frames before fixed-field reads; reject detector calibration/outlier values. No action is dispatched from invalid data. |
| Fast crossing between samples | A return crossing inferred from a sign change completes one repetition even if the 40 ms samples miss the neutral band. It cannot double-count a single oscillation. Configured amplitudes and repetition counts are unchanged. |
| Brief sample gap | Detector discards partial movement after the existing one-second stream gap; watchdog retries only if the stream remains silent. |
| Rapid context/source changes | Preserve cooldown and require stable neutral; at most one event is dispatched from a received radio batch. |
| Service destruction/recreation | Cancel controller work and test ownership, clear stream/wear state, stop only the eligible current local stream, and recreate demand from current settings and fresh connection evidence. |

## Command lifecycle and constraints

The stream controller explicitly distinguishes IDLE, STARTING, RECEIVING, RECOVERING, UNAVAILABLE and STOPPING. It tracks the selected packet variant and generation. Missing data and failed stop writes have separate bounded retry accounting. Re-enabling demand for the same service can supersede a failed stop; changing services waits for the old stop to succeed. Losing local eligibility discards pending commands.

A successful existing AACP sender return means the write/flush completed; it is not a firmware acknowledgment. Fresh decoded motion is the confirmation of a functioning stream. A generation-scoped AACP write captures the socket once, preventing an old command from being retargeted to a newly installed socket between validation and write. The normal AACP sender, Bluetooth reconnection/peer arbitration, HR transport and media-command mapping are unchanged. If its underlying socket fails, its ordinary transport cleanup/reconnection remains authoritative; head tracking never closes a shared connection just to recover its own stream.

All detector/controller deadlines use monotonic elapsed time. Connection and wear generation plus a control epoch prevent reuse of stale samples. A source/context change clears partial movement but never shortens an already-earned cooldown.

## Validation and physical matrix

Automated cases cover early wear/Connected ordering, left/both/right/out transitions, stale generation reports, missing-report refresh bounds, BLE hints/silence, healthy/silent motion, failed starts/stops, old-generation stops, packet variant switching, ownership loss, diagnostics ownership, neutral rearming, cooldown continuity, malformed framing and decoded-motion dispatch. Existing call, Gym automation, media and sensor tests remain in the full suite.

Physical checks still require the user wearing the AirPods:

- Start Pocket Casts and use Nod/Shake; remove one bud, reinsert it, then repeat with the other bud.
- Remove both and handle/move the buds: no commands. Reinsert and retry without restarting EveryPods.
- Put both in the case, reconnect to the same Pixel, then use gestures without opening/restarting EveryPods.
- Exercise a temporary AACP loss independently of BLE where feasible; confirm recovery and no peer takeover.
- Interrupt a test with a real call; check that gestures do not leak into normal/Gym commands.
- Confirm stream shutdown when the final feature/owner is disabled, and resumed reception after a fresh local connection.

Do not run the Gradle connected-test task on this personal Pixel. Its existing Espresso/Android 17 incompatibility and target-app cleanup were established earlier. Builds and deterministic lifecycle tests do not certify physical sensor behavior.


## Live evidence during this audit

The updated Pixel trace showed normal-mode demand starting the selected motion stream and reaching RECEIVING automatically while the AirPods remained connected. Both NOD→PREVIOUS_TRACK and SHAKE→NEXT_TRACK were then logged repeatedly. The user confirmed that gestures now work, with some intermittent misses. Their saved amplitude values (Nod 800, Shake 700) and two-repetition requirements were left unchanged. Successful observed patterns exceeded those thresholds. A deterministic regression also demonstrated that a neutral crossing between discrete samples could lose a repetition; the classifier now accounts for the crossing without lowering thresholds or counts.

The reinstall/startup path and live recognition are observed. The full one-earbud/both-earbuds/case-reconnect/temporary-transport matrix still requires physical completion; automated coverage is not represented as that physical evidence.

Final validation: all **575 unit tests passed**; Debug assembly, instrumentation-source compilation, baseline-aware lint and whitespace checks passed. The final APK was installed in place on the Pixel 10 without changing gesture thresholds or media commands. Android 17 UI instrumentation remains excluded for the previously documented framework incompatibility.
