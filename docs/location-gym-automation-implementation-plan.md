# Location-triggered Gym Mode — implementation handoff

Prepared 25 October 2026 for Luna. Repository inspected at `ddc9d02` in `/Users/nikos/Everypods`.

## 1. Outcome and binding product decisions

Implement an opt-in Android automation: when the user arrives at their saved gym and their AirPods are locally connected and ready, enable Gym Mode, use its existing saved stem-control scheme, and start heart rate with the same start behaviour as the heart-rate shortcut inside the case artwork.

The user chose location because they do not connect to their gym's Wi-Fi. Do not add Wi-Fi scanning, activity recognition, automatic place learning, or a general-purpose rules editor in this delivery. Keep the internal trigger/action boundary extensible to future event-based automations. Do not expose the old ANC profiles feature or couple this feature to noise-control schedules.

Latest user instruction overrides earlier discussion: heart rate must be equivalent to the case-art shortcut. Do not independently select a sharing transport, silently choose TICKR, or invent a separate background monitoring mode. Read the user's current selection at activation time:

| Existing heart-rate state/selection | Arrival action |
| --- | --- |
| Already running, recovering, or sharing | Leave it alone; never toggle it off or switch its transport |
| Inactive; a supported sharing method is explicitly selected | Start that method through the same service command and prerequisites as the case shortcut |
| Inactive; monitoring only / no configured sharing method | Use the shortcut's existing monitoring-only start path and lifecycle |
| Inactive; permission, capability, connection, ownership, or transport prerequisite missing | Do not start; display a useful reason and repair link |

This is the shortcut's **start** operation, not a literal toggle. Existing monitoring-only behaviour, including foreground/lifecycle suspension, stays the same. Do not promise background monitoring when monitoring only is selected. Selected sharing retains its existing background behaviour. Show that distinction in the rule summary using existing heart-rate descriptions.

Enabling Gym Mode applies the existing gym controls; do not copy, replace, or maintain a second set of control mappings. No automatic timer start, playback start, ANC change, or peer takeover.

## 2. Relevant current implementation

Paths below are repository-relative:

- `android/app/src/main/java/io/automated/ventures/everypods/presentation/components/HeartRateCaseControl.kt`: authoritative shortcut. Reads `configuredMethod()`, checks active/recovering/sharing, then starts monitoring or selected sharing. UI handles runtime permission requests.
- `.../presentation/components/HeartRateCasePresentation.kt`: existing presentation of monitoring/sharing/BPM.
- `.../bluetooth/hrm/HeartRateSharingPrefs.kt`: `configuredMethod()` returns nullable explicit selection; `method()` has a TICKR fallback and **must not be used to decide this automation's action**. `HeartRateSharingIntent` is intentionally memory-only.
- `.../utils/GymModePrefs.kt`: master toggle and saved gym press mappings.
- `.../services/AirPodsService.kt`: `gym_mode_enabled` preference listener calls `onGymHeartRateChanged()`, `setupStemActions()`, announcement, notification refresh. Current `onGymHeartRateChanged()` uses the independent `gymAuto` preference and `method()` fallback; avoid duplicate/contradictory starts when integrating the new rule.
- `.../services/AirPodsService.kt`: `startHeartRate()`, `startHeartRateSharing()`, `stopHeartRate()`, `hrMonitor`, sharing intent, recovery windows and ownership checks. Preserve their transport and sensor resilience.
- `.../presentation/viewmodel/AirPodsViewModel.kt`: existing heart-rate commands.
- `.../presentation/screens/CategoryScreen.kt`: canonical Smart features category (`SmartContent`, `gym_mode` and `automation` anchors). Do not implement this only in the legacy `SmartAutomationScreen.kt`.
- `.../presentation/screens/AirPodsSettingsScreen.kt`, `SettingsNavigation.kt`, `MenuSearchLocalization.kt`: navigation/search integration.
- `.../services/GymModeQSService.kt`, service stem actions and notification action: manual Gym Mode entry points.
- `.../receivers/BootReceiver.kt`, `EveryPodsApplication`: startup/reconciliation integration points.
- `android/app/build.gradle.kts`: min SDK 33, compile/target SDK 37; debug enables DIRCON, normal release disables it. Discover actual available variants before running variant-specific tasks.
- `android/app/src/main/AndroidManifest.xml`: currently no location permissions.
- `docs/design/android-ui-contract.md`: follow the existing canonical category, typography, grouping and search contracts.

Inspect these files again before editing because other work may land. Keep unrelated untracked content, `tmp/relay-wt`, camera work and VoIP reports untouched. Work in the owning checkout; do not copy private/experimental trees wholesale.

## 3. V1 scope and defaults

- One saved gym location/rule in V1; internal record has a stable UUID and schema version for future expansion.
- Rule disabled by default. Independent setting to start heart rate, on by default in a newly created rule. It means “use my heart-rate shortcut preference”; it does not change that preference or the existing global Gym Mode auto-share toggle.
- Arrival radius: 150 m default, editable 100–1,000 m with a small preset list or accessible numeric input.
- Arrival dwell: 60 seconds, internal default. OS delivery may take several minutes; this is not a response-time guarantee.
- Departure hysteresis: a second circular boundary, `arrivalRadius + max(50 m, arrivalRadius * 0.25)`. Only confirmed exit beyond that boundary ends the visit. No exact alarm or rapid location polling.
- Restore changes on departure: on by default, user can turn it off.
- Require local AirPods connection for action execution. Arrival without AirPods records presence and waits; it must not force a connection or steal ownership from a peer.
- No new premium/paywall behaviour.

## 4. User flow and UI

Add “Gym automation” under Smart features → Automation, with a short summary and a link from the Gym Mode group. Use a dedicated editor because location/permissions/session status are a complex tool. It must also be reachable while disconnected so setup does not require wearing AirPods.

Editor fields:

1. Enable/disable automation.
2. Gym name, default “Gym”.
3. Location: “Use current location”, plus editable latitude/longitude as an optional remote-setup path. Validate coordinates and expose a readable saved-location summary. Do not require a maps API key, address search provider, or remote geocoding. A full map picker is a follow-up, not a delivery dependency.
4. Detection radius, with a plain explanation that it includes the area around the building.
5. Actions: Gym Mode + existing controls; optional heart rate using current shortcut preference, displayed by name. Link to existing heart-rate settings; never duplicate transport selection here.
6. “Restore on departure”.
7. Live status and contextual action: “Off”, “Finish location setup”, “Waiting for arrival”, “Waiting for AirPods”, “Gym Mode active”, “Heart rate needs attention”, “Paused for this visit”, or “Location unavailable”.
8. Explicit “Resume for this visit” / “Stop for this visit” when applicable; these are user actions, not incidental navigation side effects.

Saving a location must not enable the rule until required setup is complete. Keep a draft when permission is declined. “Use current location” obtains a bounded single fix; reject an old or very inaccurate fix with retry guidance rather than saving a misleading centre. Cancel pending location requests on navigation/cancellation.

Rule and Gym Mode UI must observe shared state, not keep a stale `remember` snapshot after automation changes preferences. Case-art presentation remains the authority for actual HR state; show requested, starting, active and failure accurately. Navigation/search never starts HR or requests permission automatically.

Follow large-text, TalkBack, dark-mode and localization conventions. Add strings to every supported locale and speech resources only if new speech is necessary. No hardcoded product copy or raw technical exceptions. Avoid duplicate Gym Mode announcements; reuse the existing one once per actual toggle.

## 5. Permissions, platform integration and location provider

Use Google Play services `GeofencingClient` and fused location for bounded setup/reconciliation fixes. Add the current supported `play-services-location` dependency through the version catalog after checking its official release documentation and compatibility. Do not guess a version or add a continuous location service.

Manifest additions: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`; request coarse+fine together, then background separately. Geofencing needs precise access. Approximate-only permission leaves this rule in an actionable incomplete state. Request no location permission on general app startup or for unrelated features.

Permission flow:

1. Explain the requested feature and local handling of saved places before the runtime prompt. Include a prominent disclosure that location enables Gym Mode and selected heart-rate behaviour even when the app is closed/not in use.
2. Request foreground precise location only after an explicit setup action.
3. Separately explain background access and open the appropriate app permission settings for “Allow all the time”, using Android's localized option label.
4. Recheck on return; never assume opening settings granted anything. Decline/cancellation leaves the rest of EveryPods usable.
5. Offer existing HR permission setup in the foreground for the selected method. Never launch permission dialogs, activities or settings from a geofence event. Recheck prerequisites at execution because the selection/permissions may change later.

Use the shortcut's full prerequisites, including notifications, Bluetooth connect/advertise as appropriate, local-network permission for DIRCON on API 37+, supported build and transport environment. No new body-sensor/health permission is justified merely by this feature's name; follow the existing HR protocol path.

Register a non-exported explicit geofence receiver using an explicit, stable `PendingIntent` with `FLAG_UPDATE_CURRENT | FLAG_MUTABLE` (required by the geofencing API). Request IDs include rule UUID, configuration revision and boundary kind. Validate callback errors, transition type and registered current IDs. Ignore delayed callbacks from edited/deleted/disabled rules.

Register inner DWELL (60 s) and outer EXIT boundaries; initial DWELL supports enabling while already inside. Presence starts UNKNOWN until confirmed. Outer EXIT is only meaningful for ending a visit that actually became active/inside. Serialize callback handling and config updates. Do not turn an initial outer EXIT into a fictitious arrival/departure.

Use a short receiver `goAsync()` job with a guaranteed `finish()`; persist the event then dispatch work to the existing service. Geofence events are an Android background foreground-service-start exemption, but service type/prerequisites still apply. Use the existing connected-device service for AirPods work, not a location foreground service. Catch background-start/security failures and show pending/needs-attention state instead of claiming success or retrying forever.

Google Play services missing/disabled, device location disabled, registration failures or permission revocation must be distinguishable. V1 has no alternate provider: explain unavailability without breaking manual Gym Mode. Do not label a successfully saved rule as registered until registration succeeds.

## 6. Data and state machine

Suggested new package: `automation/`. Keep Android provider code separate from the pure decision engine. Suggested components (names may follow local conventions):

- `GymAutomationRule`: version, UUID, revision, enabled, label, lat/lon, arrival radius, HR-action enabled, restore-on-exit.
- `GymAutomationRepository`: atomic persistence of the rule and small runtime journal; observable state. Use a dedicated versioned JSON preference file consistent with current dependencies, not a new database just for one rule.
- `GymGeofenceRegistrar` and `GymGeofenceReceiver`: registration and event normalization.
- `GymAutomationReducer`: pure event-to-state/effects decisions, injectable clock/location evidence and stable event/session IDs.
- `GymAutomationCoordinator`: serial execution, lifecycle reconciliation, commands into AirPods service, command result handling.
- UI view model/editor and permission-flow adapter.

Track separately:

- Presence UNKNOWN / INSIDE / OUTSIDE, rule revision, current visit ID and evidence timing.
- Session WAITING_FOR_DEVICE / APPLYING / ACTIVE / PARTIAL / SUPPRESSED / ENDED.
- Requested versus successfully applied effects.
- Previous Gym Mode value, whether this visit changed it, action generation/owner and device identity.
- Whether this visit started HR, its session identity/generation, and any manual takeover/stop.
- Independent Gym Mode and HR suppression for the current visit.
- Provider/permission/registration errors and any outstanding recheck.

Persist only the minimum session journal needed to clean up owned changes and preserve manual suppression through process death. Keep actual HR broadcast intent memory-only; never replay a persisted “HR active” flag as a start command. Do not store BPM, raw device-location trails, coordinates in logs, or transport credentials in this journal.

Make every effect idempotent with visit/action tokens. A repeated DWELL, reconnect, settings observer, UI recomposition or duplicate callback must not rerun the shortcut toggle, start a second transport, or produce repeated announcements.

## 7. Arrival and AirPods readiness

On validated inner DWELL, create/retain a visit; if suppressed, leave it suppressed. If not locally connected, wait without applying settings. On local connection/control-ready changes, reconcile once against current location evidence and device capability.

For reconnect/app restart with stale or unknown presence, request one bounded current-location fix if permissions allow. Suggested fresh-fix threshold 2 minutes; use elapsed-realtime age for fixes in the current boot. Require the reported accuracy circle wholly within the arrival boundary to assert INSIDE, wholly outside the outer boundary to assert OUTSIDE; ambiguous evidence remains UNKNOWN. Cap waits (e.g. 15 s) and avoid polling loops. A fresh OS transition is usable as transition evidence; do not impose the fix-age limit as a timer that automatically ejects a stationary user from an active visit.

After confirmed presence and readiness:

1. Snapshot current settings/ownership before changes.
2. Enable Gym Mode only if off. Route through the same shared controller as manual Gym Mode, with an explicit source/owner token.
3. Existing gym press actions become effective through `setupStemActions()`; do not write mappings.
4. If the rule's HR action is enabled and no HR session is already active/recovering, run the shared shortcut-start operation with current selection and prerequisites.
5. Record actual command outcome and ownership. A start request is not proof of live BPM or a paired recipient. HR failure leaves Gym Mode usable and is reported as partial success.

Connection alone is not control readiness or AACP ownership. Wait for the same ready conditions as the shortcut, respect model HR capability, single/dual-bud constraints already enforced in the service and existing sensor/transport retry budgets. No unbounded automated start retry after those budgets fail; user can retry or the next confirmed visit can try again.

## 8. Share commands and resolve the existing Gym Mode auto-share path

Extract/reuse a single service-level “ensure heart rate started using current shortcut preference” operation. The case shortcut keeps its manual toggle UI (stop if active); automation calls only its idempotent start half. Background callers return a typed needs-permission result instead of attempting foreground permission UI. Keep UI-specific toast/launcher logic outside the service.

The existing Gym Mode preference listener currently auto-starts HR on a toggle. Integrate source-aware Gym Mode changes and HR reconciliation so the location coordinator and that listener cannot both start, start the fallback method, or stop an unrelated session. New location rule actions must execute exactly the requested shortcut semantics even if `gymAuto` is on. If the location HR action is disabled, a location-origin Gym Mode change must not indirectly start HR through the global auto-share listener. Existing user-origin Gym Mode behaviour remains covered by regression tests; prefer replacing its fallback selection with explicit configured selection where needed to avoid implicit broadcasting, and document that correction.

Update every manual entry point (category toggle, Quick Settings, stem action, notification and any other discovered writers) to use the source-aware controller. Add an HR command owner/source at the service boundary so manual stop, transport switch and manual restarts invalidate automation ownership without treating the coordinator's own preference writes as user overrides. A simple before/after boolean comparison is insufficient.

## 9. Departure, manual override and cleanup

- Confirmed outer EXIT ends the visit and releases its suppression. Never infer exit from absent callbacks, disabled GPS, denied permission or missing Play services.
- With restoration on, restore Gym Mode only when this visit switched it on and no manual change superseded it. If it was on before arrival, leave it on. Restoring normal controls means toggling Gym Mode off, not overwriting saved mappings.
- Stop HR only if this visit started the same still-owned session. Preserve pre-existing HR monitoring/sharing, user-restarted HR, manually changed transport and other owners. Cleanup must cancel owned pending starts and owned recovery work too.
- With restoration off, leave successfully applied settings running and relinquish automation ownership at departure; the rule must not later stop that session.
- Manual Gym Mode off suppresses all further activation for the visit and cleans up only this visit's owned HR as applicable. A manual HR Stop suppresses only the HR action; Gym Mode stays available.
- “Resume for this visit” explicitly clears suppression and reruns readiness checks. Merely opening an editor or changing sharing preference does not restart HR or override a manual stop.
- If the user manually changes method while active, existing HR UI/service behaviour owns that change; the rule relinquishes HR ownership and must not undo it on exit.
- Disable/delete/edit-location while active: remove old registrations, cancel pending effects and finish the old visit using the chosen restoration policy before registering the replacement. Revision checks prevent late effects.
- On location permission revocation/provider shutdown, mark location unavailable and pause new starts. End/release owned effects using the restoration policy, without claiming a geographical departure. Re-enabling location requires fresh reconciliation. Preserve suppression where the visit is not known to have ended.
- AirPods disconnect does not imply gym exit. Delegate bounded HR recovery to the existing session machinery; no peer takeover. A different AirPods device invalidates action ownership and readiness must be evaluated afresh.

## 10. Startup, reboot and failure recovery

Re-register enabled geofences after boot/package replacement, and reconcile on app/service startup and return from location settings. Existing geofences do not need minute-by-minute re-registration. Persist desired registration revision and reconcile failures with bounded retries, no tight loop. Handle Play services resetting registrations.

After process death, restore rule configuration, session ownership journal and suppression, but revalidate presence/device state before new actions. Never resume a heart-rate broadcast solely from a persisted visit. Preserve the existing memory-only sharing-intent contract: a fresh verified arrival can start a new automation action; for an interrupted active visit after cold restart, show “Resume heart rate” until explicit user resume, rather than silently replaying the old session. State this limitation in validation/results.

After reboot, discard elapsed-time evidence from the previous boot; retain only configuration and safe cleanup/suppression metadata. Do not equate a new registration or UNKNOWN with a new visit that clears suppression. Obtain fresh presence evidence before acting.

Force-stop can prevent callbacks until the user opens the app again; do not promise recovery around it. Reconcile on next launch. Never display “active” purely because a preference says enabled. Cleanup/register/start failures need independent diagnostic states and a useful retry path.

## 11. Privacy and release integration

Store the gym centre and rule on device, without route history or location analytics. Reuse `allowBackup=false`; do not add cloud/peer sync of locations. Redact location from ordinary logs and diagnostics exports. Explain that Android/Google Play services supplies detection; do not claim no platform processing or no network activity.

Update the existing privacy page and in-app permission explanations with actual behaviour. Background location in the Play artifact requires a declaration, prominent disclosure, privacy-policy coverage and review; approval is not guaranteed for an optional feature. Prepare the required review demonstration/checklist as release documentation. Do not publish, submit a declaration, install or release without the relevant user request. If a build excludes this feature for distribution, exclude the background-location permission and registration code path together, not merely the UI.

Do not add a separate continuously running location notification/service. Existing Gym/HR notifications should reflect actual state and expose user stop actions through existing patterns. Notification permission denial must be an explicit prerequisite/failure where the equivalent shortcut requires it.

## 12. Implementation order

1. Reinspect branch/instructions and existing manual Gym/HR paths; write focused characterization tests for shortcut selection and existing session ownership.
2. Extract the shared idempotent HR-start operation and source-aware Gym/HR commands; verify the case shortcut and manual controls retain behaviour.
3. Add rule repository, pure reducer, session ownership/suppression and effect result model. Test before wiring platform callbacks.
4. Add dependency, permissions, geofence registrar/receiver and bounded location adapter; fake provider interface for tests.
5. Integrate service readiness, startup/boot, revision cancellation and cleanup; verify no automatic default sharing or duplicate global `gymAuto` path.
6. Add canonical UI, disconnected access, search, permission education, live state and localization. Add privacy/release documentation.
7. Run appropriate automated checks; collect physical-device evidence and report remaining physical tests explicitly. Produce a reviewable change with implementation and validation notes; do not substitute a mock for actual geofence integration.

## 13. Required automated tests

Use deterministic fakes for location provider, clock, permission state, service readiness and action ownership. Test decision outcomes, not Android API call mirroring.

- Rule serialization/validation, disabled/draft/registration-failure states, malformed storage and config revision invalidation.
- Inner dwell → waiting device → ready → one Gym Mode enable and one selected HR start; duplicate events do nothing.
- Already-active/recovering HR never toggled off, restarted, transport-switched or claimed by the rule.
- Explicit TICKR/Bluetooth/DIRCON selection follows shortcut behaviour; no selected sharing method follows monitoring-only behaviour; unavailable DIRCON obeys supported-build selection semantics without defaulting to another broadcaster.
- All shortcut prerequisites match between manual and automated start; background missing permission produces status, not activity launch.
- Location-owned Gym Mode activation with global `gymAuto` on/off and location HR action on/off: no double start, implicit TICKR or unintended HR when disabled.
- Existing manual Gym Mode, stem arbitration, timer controls, announcements and HR recovery retain their documented behaviour.
- Manual Gym off, HR Stop, explicit resume, manual transport change and manual restart preserve correct independent suppression/ownership.
- Pre-existing Gym Mode/HR survive departure; only owned effects restore/stop; restore-off relinquishes ownership.
- Inner/outer boundaries, initial DWELL/EXIT, ambiguous fix, stale fix, out-of-order/old-revision callback, edit/delete/disable races and pending start cancelled on exit.
- Process restart/reboot/permission revocation/location off/provider error never replay persisted HR intent or fabricate exit; registration recovery and fresh presence rules work.
- Canonical navigation and search work while disconnected, no navigation side effects; UI accurately observes external Gym/HR changes, partial success and denied permissions.

## 14. Checks and physical acceptance

Discover the installed JDK/toolchain and current Gradle tasks; do not change signing or release versions for this feature. Expected checks from `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
python3 scripts/validate_localization.py
```

Also build/test supported Play and minified release variants where the local toolchain permits, including a build without DIRCON. Inspect merged manifests for intended permissions/receiver export and run diff checks. Record existing unrelated failures separately; do not widen lint baselines to hide new failures.

Physical acceptance matrix: Pixel and Xiaomi, actual OS versions recorded, with HR-capable AirPods. Cover:

1. Set location at gym; deny/approximate-only/allow precise/background permission flows and settings return.
2. Screen locked, app backgrounded: arrive with AirPods already connected; record detection delay and actual Gym controls, actual fresh BPM and selected sharing transport state.
3. Arrive without AirPods; connect them inside and verify one activation after actual readiness.
4. Leave beyond outer radius; verify restoration and that pre-existing/manual sessions survive.
5. Walk near boundary/pass nearby, remain stationary, temporarily lose GPS, disable location and revoke permission.
6. Manual Gym off, case-art HR Stop and explicit Resume; verify no automatic restart within the suppressed visit.
7. Selected sharing methods that are available on each build/device; missing Wahoo, BLE permission, unavailable local network, selected monitoring-only behaviour with screen off.
8. Process kill, reboot, force-stop/reopen, Bluetooth loss, device change, existing recovery budget and no surprise persisted-session broadcast.
9. Editing/disabling/deleting an active rule; duplicate callbacks; Android battery saver and Xiaomi background restrictions.
10. Dark/light themes, large font, TalkBack, all locale resource checks, case artwork and category state staying in sync.

Simulated locations and injected fake transitions prove wiring/reducer logic, not real gym detection or fresh physiological readings. Do not present compile, install, advertised transport readiness or cached BPM as physical acceptance. Implementation can be delivered before a real visit, but list that validation as outstanding.

## 15. Completion criteria and Luna's report

Deliver working rule creation/registration, background arrival and departure handling, shared shortcut-equivalent HR activation, existing gym controls, ownership-safe cleanup, manual overrides, lifecycle recovery, localized UI, privacy documentation and tests. Summarize changed files and behaviour, checks with results, physical evidence and remaining limitations. Specifically report that no default sharing method is selected, duplicate arrival cannot stop HR, and a location rule does not overwrite the user's heart-rate preference or control mappings.

## Official references (checked 25 October 2026)

- [Android geofencing: permissions, dwell/exit, mutable pending intent, radius, delivery limits and re-registration](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [GeofencingClient API contract](https://developers.google.com/android/reference/com/google/android/gms/location/GeofencingClient)
- [Foreground/background location permission flow](https://developer.android.com/develop/sensors-and-location/location/permissions/background)
- [Background foreground-service start restrictions and exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 17 target behaviour changes; target API 37 must be checked](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Google Play background-location review and disclosure requirements](https://support.google.com/googleplay/android-developer/answer/9799150)

These sources constrain the platform integration; session ownership, UI defaults and restoration policy above are EveryPods design decisions. Recheck current dependency/API documentation at implementation time.
