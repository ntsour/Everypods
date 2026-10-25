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
