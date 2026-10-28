# Wi-Fi relay network recovery and permissions

## Findings and implementation plan

1. Make Automatic, shared Wi-Fi, and device hotspot choices permanently accessible
   in each Wi-Fi connection's options, with a direct Home shortcut. Do not depend
   on a transient FAILED state that receiver preparation can replace.
2. Keep saved policy separate from negotiated session status. A negotiated hotspot
   fallback must not replace the user's Automatic selection. Explicit sender policy
   wins; an automatic sender honors a receiver's saved manual override.
3. Surface local-network permission in initial setup and Settings → Permissions,
   including its current grant and the existing request/app-settings recovery flow.
   Do not make optional relay access a requirement for using Bluetooth features.
4. Refresh grant state when returning from Android settings. Show the same recovery
   entry on Home and in Wi-Fi relay options. Guard receiver preparation, source
   startup, authenticated route negotiation, and WebRTC transport creation in the
   service as well as screen entry points.
5. Verify OS permission boundaries, saved-policy persistence, and sender/receiver
   overrides with regression tests. Run the normal unit/localization/lint/build set.
   Verify the UI on a connected device; do not equate that with relayed audio.

These changes are implemented in this branch. Changing a network selection stops
an affected session and saves the policy for the next Start. Default automatic
selection and available-hotspot fallback remain enabled.

## Android permissions

- INTERNET and ACCESS_NETWORK_STATE are normal manifest permissions, already present.
- Android 17 / API 37, with this app targeting 37, requires the dangerous runtime
  ACCESS_LOCAL_NETWORK grant for local sockets on both relay devices. It was already
  declared; setup and settings now expose it. The existing foreground permission
  requests and the service now consistently check the same requirement.
- Earlier supported Android versions do not require this new runtime grant under
  the app's normal configuration. No Android 16 opt-in compatibility flag is enabled.
- NEARBY_WIFI_DEVICES is not needed by the current implementation: it does not call
  local-only hotspot creation, Wi-Fi Direct, Wi-Fi Aware, or Wi-Fi management APIs.
  The user enables/joins Wi-Fi or hotspot through Android settings. This change
  does not automate hotspot creation or require location for relay.
- RECORD_AUDIO and per-session Android playback-capture consent are still required
  on the sender and requested when capture begins. These are separate from LAN access.

Reference: https://developer.android.com/privacy-and-security/local-network-permission
and https://developer.android.com/develop/connectivity/wifi/wifi-permissions

## Physical acceptance

Check both devices: an existing Wi-Fi connection's Network options must be reachable
before Start, after Stop, after failure, and after reopening settings. Exercise shared
Wi-Fi and an already configured hotspot, then select Automatic and confirm a successful
fallback does not change the saved selection. Deny/revoke LAN access on each endpoint
on Android 17: no transport should start, the local UI must explain the required grant,
and Settings → Permissions must restore access without recreating the connection.
Verify playback-capture permission and per-session consent independently. Finally,
confirm audible audio and stable routing on both shared Wi-Fi and hotspot.

## Validation record (2026-10-27)

- `:app:testDebugUnitTest :app:validateLocalizationResources :app:lintDebug
  :app:assembleDebug` passed: 582 tests, zero failures/errors/skips. Lint used
  the existing baseline and completed without blocking errors.
- On Pixel 10 / Android 17, observed all three network choices with the peer
  offline, the Home Network options entry, and Local network in Permissions.
  The Pixel's local-network grant was already allowed; it does not establish the
  cause of the reported earlier failure.
- Installed the same build on Xiaomi / Android 16, preserving app data, and
  verified its installed APK hash. No separate LAN runtime row is shown on API 36.
- Audible relay playback and denied/revoked-permission recovery across both physical
  devices remain pending; unit checks do not prove these end-to-end behaviors.
