# Teams mute synchronization

Validated on Pixel 10 over USB, 23 October 2026, with the Relay/DirCon preview
based on remote main 19b9ffc (merged PR #48).

## Diagnosis

Teams' Spanish ongoing-call notification uses `Desactivar audio` (mute) and
`Reactivar audio` (unmute). These exact values were read from the installed
Teams APK's Spanish resources and observed in live notifications. The previous
keyword matcher recognized neither, discarded the call notification, and logged
`setMuted(true): no cached action` after receiving an AirPods stem press.

## Resolution order

1. Android Notification.Action semantic mute/unmute metadata.
2. Teams resource icon names observed in live USB logs:
   - `icn_callaction_audio_off`: currently muted, button unmutes.
   - `icn_callaction_audio`: currently unmuted, button mutes.
   - `icn_callaction_endcall`: hang up.
3. Exact labels read from Teams' own resources, using
   `notifications_call_mute_title` and `notifications_call_unmute_title`.
   Resolve these for every locale in EveryPods' generated `supported_locale_tags`
   array (en, de, el, es, fr, it, pt, tr, uk, vi, zh-CN, zh-TW).
4. Existing multilingual keyword matching for legacy app notifications.

No translations are invented for the Teams resource fallback. Android uses Teams'
default-language resources when the corresponding language split is not installed.
Resource names belong to Teams and may change in a future update; they are not a
public compatibility contract. Exact localized strings are loaded again for each
notification, so newly installed language resources can be used without restarting.
The Pixel's Teams notifications report semantic=0, so semantic metadata alone
cannot resolve its mute state.

The first detected call mute state now invokes reconciliation, rather than waiting
for a later state change. Existing echo suppression behavior is otherwise unchanged.

## Validation

The six CallNotifListener regression tests cover Spanish labels, initial state,
subsequent toggles, duplicate notifications, semantic metadata, verified icon names,
and resource lookup for every supported locale. RelayPreview unit tests and APK
assembly passed. USB logs showed a stem press firing Teams' `Reactivar audio`
action and the notification changing to `Desactivar audio`; the user confirmed
synchronization works with the installed test build.

Microsoft's public microphone help explains the controls but does not establish
Android notification wording for every locale:
https://support.microsoft.com/en-us/teams/meetings/mute-and-unmute-your-mic-in-microsoft-teams
Android's semantic action API:
https://developer.android.com/reference/android/app/Notification.Action
