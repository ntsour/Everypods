# Gym location automation — QA test plan (debug build)

Branch `feature/gym-location-automation`. Spec: `docs/location-gym-automation-implementation-plan.md`.
Debug build only (`io.automated.ventures.everypods`, `-debug` version suffix). Record device, Android version and build SHA for each run.

## What the feature does
- One saved gym zone (geofence). Inner circle = radius (default 150 m, presets 100/150/250/500/1000 m); arrival is a 60 s DWELL.
- Outer circle = radius + max(50 m, 25 %); an EXIT there starts a **3 min departure grace**. Coming back (DWELL) inside the grace cancels departure.
- Condition: AirPods locally connected (AACP link). Arrival without AirPods waits; connecting later activates once.
- Actions: Gym Mode on (existing saved gym press controls apply). Heart rate: **only** the explicitly selected sharing method (Heart rate settings) is started, via the same service command as the case-art shortcut. No selection → no HR. HR already running → left alone, not owned.
- Departure (restore on): Gym Mode off only if the rule turned it on; HR stopped only if the rule started it.
- Manual Gym Mode off during a visit → paused for that visit (no re-activation until departure or “Resume for this visit”). Manual HR stop → HR not restarted for the visit.
- Unknown/missing signals (geofence error, location off, permission revoked) never count as departure.
- Process death: journal restored, HR never replayed. Reboot: presence reset to unknown, suppression kept; needs a fresh DWELL.

## Setup
1. Smart features → Gym Mode → **Gym automation** (also under Automation, and in search).
2. Tap **Allow precise location** (foreground prompt), then **Open location settings** → Permissions → Location → “Allow all the time”. Return; screen rechecks.
3. Save a location (Use current location, or type lat/lon → Save coordinates). Toggle **Enable automation** (disabled until location + background permission).
4. Heart rate settings: pick a sharing method (or none) depending on the case.

## Logcat
```
adb logcat -s EveryPodsGymAuto EveryPodsHRM AirPodsService
```
`EveryPodsGymAuto` logs every event, presence and effects (never coordinates): `event=Arrived presence=INSIDE effects=[EnableGym, StartHr]`.

## Simulating location
`adb emu geo` doesn't work on real phones. Two options:
- **Debug hook (preferred for logic/wiring)** — debug builds only, exported receiver guarded by `android.permission.DUMP` (adb shell has it). Same receiver → coordinator path as real geofences:
  ```
  A=io.automated.ventures.everypods.debug.GYM_AUTOMATION
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd configure --ef lat 40.4168 --ef lon -3.7038 --ei radius 150 --ez enabled true
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd arrive      # inner DWELL
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd exit        # outer EXIT (grace starts)
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd grace       # force grace re-check (only departs after 3 min)
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd unavailable # location error
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd resume
  adb shell am broadcast -a $A -p io.automated.ventures.everypods --es cmd status
  ```
  `configure` with `enabled true` works without background permission in debug (it bypasses the UI gate, not the geofence registration, which still requires the permission).
- **Real geofences** — use a mock-location app (Developer options → Select mock location app, e.g. “Lockito”) and move the mock point inside/outside the radius. This exercises Play services geofencing; delivery can take minutes. A real gym visit is still required for acceptance.

## Cases (expected result → evidence)
| # | Case | Steps | Expected |
|---|------|-------|----------|
| 1 | Arrive with AirPods connected | AirPods connected, method = Bluetooth; `arrive` | Gym Mode on once, gym press actions active, HR sharing starts; log `effects=[EnableGym, StartHr]`; one Gym Mode announcement |
| 2 | Arrive, no method selected | Clear sharing selection; `arrive` | Gym Mode on, **no HR** |
| 3 | Arrive, HR already running | Start HR from case art; `arrive` | Gym Mode on, HR untouched; after depart HR still running |
| 4 | Gym already on | Turn Gym Mode on; `arrive`; `exit`; wait 3 min; `grace` | Gym stays on after departure |
| 5 | AirPods connect later | AirPods in case/disconnected; `arrive` (status “Waiting for AirPods”); connect AirPods | Activates once on connection |
| 6 | Depart with grace | After case 1: `exit`; `grace` immediately | No change (“Leaving the gym”); after 3 min `grace` → Gym off, HR stopped |
| 7 | Return during grace | `exit`, then `arrive` within 3 min | Departure cancelled, nothing toggled |
| 8 | Restore off | Disable “Restore on departure”; arrive, depart | Gym Mode and HR keep running |
| 9 | Manual override | After case 1, turn Gym Mode off manually | Status “Paused for this visit”; `arrive`/AirPods reconnect do nothing; “Resume for this visit” reapplies; after departure next arrival works |
| 10 | Manual HR stop | After case 1, stop HR from case art | HR not restarted during the visit; departure still turns Gym off |
| 11 | Unknown ≠ left | `exit`, then `unavailable`, then after 3 min `grace`; also toggle device Location off | Nothing is restored; visit continues |
| 12 | Process death | After case 1: `adb shell am kill io.automated.ventures.everypods` (or force-stop + reopen) | No HR restart; Gym Mode state kept; later departure restores Gym Mode |
| 13 | Reboot | After case 1, reboot | Geofences re-registered on boot (log `geofences registered`); nothing re-applied until a fresh DWELL; suppression (case 9) preserved |
| 14 | Permissions denied | Deny precise / pick approximate / keep “While using” | Enable toggle stays disabled with “Finish location setup”; app otherwise usable; no prompt from background |
| 15 | gymAuto interaction | Turn on Heart rate “auto-share in Gym Mode”, rule HR action off; `arrive` | Gym on, no HR (rule decides HR for location-origin Gym Mode) |
| 16 | Edit/disable while active | During a visit change radius or disable | Owned changes restored, old geofences removed, new revision registered |
| 17 | Locales/a11y | EN/ES/EL (+others), large font, TalkBack, dark | All strings translated, no clipping |
| 18 | Xiaomi | Repeat 1, 5, 6, 12 with MIUI battery restrictions default and “No restrictions” | Record delays/failures |

Simulated transitions prove wiring and decisions, not real detection or fresh BPM; a physical gym visit stays outstanding.

## Permission entry cases (Permissions screen)

The optional "Background location (optional)" row lives in the **Special access** group of the
Permissions screen. The same screen is the first-run onboarding step and Settings > Permissions,
so both places show the same row. The row and the Gym automation editor both read
`GymLocationPermission.step()`. The Permissions screen polls once per second, and the editor
re-checks on resume.

| # | Case | Steps | Expected |
|---|------|-------|----------|
| P1 | First-run entry | Fresh install (or clear onboarding) → onboarding Permissions screen | Row shown under Special access with explanation + "Step 1 of 2". **Continue** works without granting it; onboarding completes. Nothing is requested until the row's Grant is tapped. |
| P2 | In-app entry | Settings → Permissions | Same row, same status as the Gym automation editor. |
| P3 | Deny | Tap Grant → deny precise location | Row stays "Grant", step 1 text. Tap again → dialog again; if Android no longer shows it, App info opens instead. Rest of app unaffected. |
| P4 | Grant (two-step) | Tap Grant → allow precise → row shows "Step 2 of 2" → tap Grant → App info opens → Permissions → Location → "Allow all the time" → back | Row shows ✓ within ~1 s. Gym automation editor shows the toggle enabled-able (no background hint). |
| P5 | Revoke and return | With ✓, system Settings → app → Location → "Allow only while using" (or "Don't allow") → back to app | Permissions row returns to step 2 (or step 1). Gym editor on resume shows hint again; geofences are re-synced (`EveryPodsGymAuto` logs removal). The rule is not silently re-requested. |
| P6 | Sync | Grant from the Gym editor, then open Permissions (or vice versa) | Both show the same state without restarting the app. |

## Map picker cases

| # | Case | Steps | Expected |
|---|------|-------|----------|
| M1 | No permission | Revoke all location → Gym automation → Pick on map | Map loads; tap places pin + circle; Save pinned location stores coordinates (fields update). No permission dialog. |
| M2 | Radius live | Map open with pin → tap radius presets | Circle resizes immediately. |
| M3 | Use map center | Pan map → Use map center | Pin moves to centre. |
| M4 | Offline | Airplane mode → Pick on map | "Map tiles could not be loaded…" shown; typed coordinates and Use current location (with GPS) still work. |
| M5 | No idle network | Close map; leave app; trigger debug arrive/exit | No tile requests (`adb logcat -s OsmDroid` silent); geofence logs unchanged. |
| M6 | Attribution | Map open | "© OpenStreetMap contributors" visible. |

## Correctness cases (HR ownership, shared start, reconnect fix)

Logcat: `adb logcat -s EveryPodsGymAuto EveryPodsHRM` (`ensureStarted source=… decision=…`, `presence fix=…`).

| # | Case | Steps | Expected |
|---|------|-------|----------|
| C1 | Shortcut parity | With a sharing method selected and HR off, tap the case-art heart; separately trigger `arrive` | Both log `ensureStarted … decision=Start` and start the same method. With HR already running, `arrive` logs `AlreadyRunning` and HR keeps running |
| C2 | No selection | Clear the sharing selection; `arrive` | Gym on, `decision=NoneSelected`, no HR. Tapping the case heart still starts monitoring only (unchanged manual behaviour) |
| C3 | Missing permission | Revoke Nearby devices / notifications for the app, selected method set; `arrive` | No HR, no permission dialog from background; `decision=NeedsPermission`; Gym Mode still on |
| C4 | Manual takeover | After case 1, stop HR on the case art and start it again (or switch transport in Heart rate settings); then `exit` + 3 min | Gym Mode restored; the manually (re)started HR keeps running |
| C5 | Stops that are not overrides | After case 1, `exit` + 3 min (rule stops HR) then `arrive` again | HR starts again on the new visit (the rule's own stop and the Gym-off cleanup do not count as a manual stop) |
| C6 | Notification stop | After case 1, stop HR from the HR notification | Same as case 10: no HR restart in this visit |
| C7 | Reconnect fix, inside | Rule enabled, standing inside the zone, presence unknown (fresh install of the rule, or after reboot); connect AirPods | One `presence fix=INSIDE` within ~15 s, then Gym on once. After reboot with a suppressed visit, suppression is kept and HR is not replayed |
| C8 | Reconnect fix, outside / poor fix | Same with AirPods connected well outside the zone; repeat indoors with weak GPS | `presence fix=OUTSIDE` (nothing applied) or `UNKNOWN` (nothing changes). Reconnecting again within 2 min triggers no second fix |
| C9 | Process death during a visit | After case 1, `am kill`, reopen, reconnect AirPods | No HR restart; Gym Mode kept; "Resume for this visit" restarts HR via the shared step |
