# Head Gestures overhaul

## Specification

Android Head Gestures move from Smart Features into Controls. The order is Stem Actions, Listening Mode Configuration, Head Gestures, Call Actions, Head Gesture Configuration, Controls Configuration. Nod (yes) and Shake (no) are global inputs, without a left/right assignment.

Normal and Gym controls each offer Do nothing and every existing `StemAction`: Play/Pause, Next/Previous Track, Digital Assistant, Google Voice Command, Cycle Noise Control Modes, Mute/Unmute, Toggle Gym Mode, Timer Start/Stop, Lap, Reset. Existing stem choices and firmware arbitration remain unchanged. Mute is a no-op outside a call. Gym assignments default to Do nothing and never inherit normal actions. The existing `gym_press_actions` route is retained under the title Gym Controls; resetting it also resets its gesture assignments.

Call Actions retains the existing stem settings and adds None/Nod/Shake selectors for Answer, Decline, Mute/Unmute, Hang Up. Answer/Decline apply while ringing; Mute/Hang Up apply during a connected call. Assigning an occupied gesture clears the previous assignment in the same call state. Calls consume unassigned gestures: there is no fallback to general actions. Call assignments are shared across normal/Gym modes; active Gym tuning still applies.

Controls → Head Gestures also includes **Shake to stop spoken announcements**, enabled by default and governed by the master switch. Calls take priority over speech cancellation, including when a call gesture is unassigned. Outside calls and safe testing, an audible spoken announcement consumes Shake before its Gym/normal assignment; Nod retains its assigned behavior. When this toggle is off or speech is not audible, the normal/Gym assignment applies. This uses the same System TTS/ElevenLabs audible-state check and stop path as the existing stem cancellation, clearing pending speech too. Detection is requested during audible speech even if both general mappings are Do nothing. Active normal/Gym tuning applies. Speech boundaries discard partial movement while preserving cooldown to prevent the cancelling shake from firing a second action.

Head Gesture Configuration retains `head_tracking`, with optional `gym=true` for the Gym tuning profile. Each gesture has an amplitude threshold (200–2000 relative units, step 100; default 800), repetitions (1–4; default 2), and cooldown (500–5000 ms, step 500; default 2000). Higher thresholds/counts require deliberate motion. These values are not calibrated degrees. Separate Gym tuning is opt-in, copied from normal settings when first enabled and preserved while disabled. Reset tuning affects only the selected editable profile.

The explicit safe test displays recognition, relative amplitude, repetition progress, and cooldown. Assigned actions are suppressed during testing. Real calls cancel testing. Leaving/cancelling releases test tracking. One collapsed Advanced and diagnostics disclosure contains the safe test, alternate-packet option, and orientation/motion diagnostics, with no nested Diagnostics expander. Opening it requests diagnostic tracking; collapsing it cancels the safe test and releases only diagnostic tracking ownership, preserving any tracking still needed by enabled actions. The manual Enable head tracking switch is removed; tracking is derived automatically from enabled features in the active context and explicit test/diagnostic ownership.

## Implementation guide

### Models, preferences, migration

`HeadGesture`, `GestureTuning`, `HeadGestureSettings`, call actions, contexts, and dispatch results are typed. The gesture mapping layer represents Do nothing as a nullable `StemAction`, serialized as `NONE`; stem persistence is unchanged. `HeadGesturePrefs` owns versioned migration and all gesture preferences in the existing settings file. Unknown actions resolve to Do nothing; numeric tuning is bounded and quantized.

Migration runs before service/ViewModel configuration and before UI preference snapshots. It preserves the master switch; general/Gym assignments default to NONE. Existing incoming-call configuration becomes Nod=Answer, Shake=Decline. Existing active-call configuration becomes Shake=Mute toggle, Nod=None, Hang Up=None. Disabled legacy features remain unassigned. Fresh installations do not acquire call assignments. A dismissible inline migration notice explains the mute change to affected enabled users. Legacy feature booleans are no longer read by runtime configuration.

The entire settings snapshot is observed by Compose, so a conflicting assignment updates both rows. Head gesture settings use SF Pro at 16sp Normal for labels, 13sp Normal for supporting text, external semantic headings, one 18dp group surface, wrapping choice rows, blue inline actions, and modal choice menus. Configuration sits directly below Call actions. Tuning updates flow to the same service controller. The schema marker makes migration repeatable without overwriting configured actions.

### Detection and dispatch

`GestureDetector` is Android-independent, accepts an injected monotonic clock, and emits typed gestures and progress. Each axis tracks bounded scalar state rather than accumulating unbounded samples. A repetition requires threshold excursions in opposite directions, followed by a return within 25% of the threshold. The configured repetition count is mandatory at every speed. Partial sequences expire after 1.5 seconds without a qualifying excursion and six seconds overall. Intended peak-to-peak motion must be at least 1.5 times cross-axis motion. Cooldown and 300 ms neutral are both required to rearm. Calibration/outlier values and malformed packets discard partial state; stream gaps over one second reset incomplete motion.

`AirPodsService` owns one detector and serialized controller. Context priority is ringing, active call, explicit test, Gym, normal. Before dispatch, context/settings/eligibility are checked again. A context or profile transition clears partial motion. General actions use the same executor as stem controls. Call actions use existing answer/reject/mute paths, preserving cellular/VoIP integration and mute-state feedback.

Tracking has independent gesture, test, and diagnostic-screen owners. The old manual-setting owner is retired in schema version 2, without changing assignments or the master switch. Stopping one owner must not stop another. Tracking runs only with supported, locally connected AirPods; action recognition additionally requires a known worn earbud. Wear checks do not depend on automatic playback ear detection. Idle monitoring never requests peer takeover. Disconnect/reconnect, call state, Gym changes, preferences, test cancellation, and service destruction reconcile ownership. A light controller tick detects notification-only call and audible-speech transitions even without new motion packets. Removing the final assignment for the active context, disabling the master, ending speech, or ending the final test/diagnostic request releases tracking. Normal bindings work outside calls and Gym Mode.

### UI and localization

Shared action labels supply both normal/Gym head selectors. Controls uses stable search anchors for assignments and configuration; configuration adds amplitude/repetition/cooldown and Gym tuning destinations. Call layout owns its header and rows in one vertical container. Pickers stack labels and values for translation/large-text resilience. Respect HEAD_GESTURES capability and existing Premium/AACP restrictions. Saved values stay visible when editing is disabled.

## Validation

Automated validation covers detector thresholds, fixed repetition counts, spikes, diagonal movement, timing expiry, cooldown, neutral rearming, invalid samples; migration combinations/idempotency/unknown values; routing and conflict resolution; and tracking eligibility/ownership decisions. Run Android unit tests, Debug assembly, and lint. UI checks cover ordering, call titles, reset behavior, action choices, search, supported locales, dark mode, TalkBack and large fonts.

### Physical release checklist (pending)

No Android device was connected over ADB at implementation start. Do not treat builds or synthetic motion tests as evidence of physical recognition reliability.

Record phone/Android/AirPods model, tuning values, intentional attempts/successes, unintended activations, battery/tracking observations, and result for each scenario:

- Nod/shake in normal and Gym profiles; each general action; disabled/unassigned gestures.
- Walking, talking, looking around, bending, and exercise without intentional gestures.
- Locked screen, one/two earbuds worn, removal/case insertion, disconnect/reconnect, service restart.
- Incoming answer/decline and active mute/hang-up on cellular and supported VoIP apps.
- Ringing-to-active transition; real call interrupting the test; no general-action fallback.
- Existing stem actions/firmware call mapping, Gym switching, and simultaneous diagnostics ownership.

The initial tuning defaults are provisional. Physical validation is required before release.

## Implementation validation — 26 October 2026

Implemented normal/Gym gesture assignments, call assignments/conflict clearing, tuning and safe test UI, versioned migration, tracking ownership, search destinations and localized resources in English plus all eleven supported translations.

Final command:

```sh
./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain
```

Results: **BUILD SUCCESSFUL**. All **382 unit tests passed**, including **25 gesture detector, migration, routing and ownership-policy tests**. Debug APK assembled; instrumentation test sources compiled. Lint passed with the existing baseline applied (282 warnings and one hint remain, including style suggestions in new preference code). Gesture resource keys and format placeholders match across all eleven translations. `git diff --check` passed.

Instrumentation UI tests were compiled but not executed. No ADB device and no configured emulator were available. Visual dark-mode/large-text/TalkBack inspection, native-language review, physical recognition reliability, locked-screen/call integration, exercise false-positive rates, and battery observations remain pending. The checklist above records the remaining release validation; no live-device success is claimed.


## 26 October follow-up: announcement priority and automatic tracking

Restored the configurable Shake-to-stop announcement action. Priority is call controls, then audible announcements, then Gym/normal actions. Safe testing continues to suppress all actions. Speech cancellation retains cooldown so a single movement cannot also activate its assigned action.

Moved configuration below Call actions and aligned the new controls with `docs/design/android-ui-contract.md`. Tests and diagnostic/packet tools are collapsed by default. Removed the manual tracking switch and its persistent override; stream demand is derived by the controller. Normal head actions require no call or Gym Mode.

The connected Pixel's Android 17 cannot run the existing Espresso 3.6.1 input injector (`InputManager.getInstance` is unavailable). The attempted Gradle connected-test task also uninstalled its target application during cleanup. The application was reinstalled; the user declined settings recovery. Do not use the Gradle connected-test task on this personal device. Future UI automation must use an isolated test application/device and a compatible framework. No physical-gesture or successful UI-instrumentation result is claimed for that run.

Final UI/automatic-tracking follow-up validation: Debug assembly, instrumentation-source compilation and baseline-aware lint succeeded. All 391 unit tests passed with no failures/errors, including automatic release after removing the final normal/Gym/call assignment and migration of the retired manual tracking preference. All eleven translation files have matching resource keys and format placeholders. The Pixel disconnected before the final revised APK could be installed; no successful device UI/gesture validation is claimed.


## Main integration — 26 October 2026

Integrated upstream `main` through `baa277b3` (PRs #53–#57) into `codex/head-gestures-main`, retaining the configurable gesture overhaul and all subsequent UI refinements. This restores location-based Gym automation, its multi-zone map editor, permissions/navigation/search, HR ownership and shared shortcut start behavior, plus main's media resume and VoIP routing fixes.

The new gesture controller now consumes main's validated `HeadMotionProtocol` protobuf sensor samples, including variable-length and batched motion frames; it no longer reads fixed offsets from enclosing packets. It uses the latest incoming-call callbacks and safe answer/decline implementations. Normal/Gym/announcement assignments remain configurable. The single Advanced and diagnostics disclosure and Controls order (Stem actions → Listening mode configuration → Head gestures → Call actions → Head gesture configuration → remaining configuration) are retained.

Schema version 3 preserves an explicit announcement-cancellation setting from main when no newer configurable preference exists. The Gym Mode category toggle observes preference changes made by location automation.

Validation: Debug assembly, instrumentation-source compilation and baseline-aware lint succeeded. **536 unit tests passed**, including upstream Gym automation/search/HR ownership and VoIP tests, existing gesture tests, a new decoded-motion-to-action pipeline regression test, and preference migration tests. `git diff origin/main --check` passed; upstream-generated aboutlibraries whitespace remains unchanged. No connected instrumentation task, preference reset, simulated geofence, or physical arrival test was run for this integration. Pre-existing untracked handoff/report files that now exist on main were preserved in a named Git stash before merging.

Installed the merged Debug APK on the connected Pixel 10 using `adb install -r` (Success). MainActivity launch returned Status: ok; the app remained running with no fatal exception recorded for the current process. This preserves current app data. Actual gym arrival/departure and physical head gestures remain separate live checks.


## Head-tracking resilience follow-up

See [the complete signal/command lifecycle audit](head-tracking-lifecycle-audit.md) for reconnect, one/both-earbud, BLE/AACP loss, ownership, recovery, neutral rearming and background-tool cases. Added generation-bound writes, early wear retention, bounded stream/stop recovery, stale-epoch rejection and correct sampled-neutral repetition counting. Safe tests no longer remain active after backgrounding the tool. Gym mode now exposes an active-profile hint without changing mappings. All 575 unit tests and the build/lint checks passed; updated APK installed on Pixel 10. Media commands and saved sensitivity values were left unchanged.
