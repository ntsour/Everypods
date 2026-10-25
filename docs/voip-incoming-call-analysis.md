# Incoming VoIP calls: analysis and implementation plan

Research began 24 October 2026 against EveryPods commit `c804287`, including the
existing Teams mute fix. Implementation and USB validation updates are recorded
below; unrelated checkout changes were left untouched.

## Implementation update: 25 October 2026

The changes are implemented on `codex/voip-incoming-call-fix`, synced to current
remote `main` at `29be39e7` (PRs #53 and #54). Notifications own per-key
snapshots, CallStyle determines stage,
and action position is never guessed. Teams fallback reads verified vendor
resources across all 12 supported app locales. Pending actions are cleared on
stage/intent replacement, notification removal and listener disconnect.

Incoming reject uses the current notification's Decline action and never sends
an answer-capable short headset event. Activity call actions use Android API 36+
ALLOW_ALWAYS to transfer an existing background-launch exemption; this fixes the
reproduced locked Viber answer failure on this Pixel, where overlay permission
was already granted. The opt-in does not grant that permission itself. The
WhatsApp Telecom headset fallback is suppressed as soon as system/audio state
shows an active call, even if the app's incoming notification lags behind.

The compile, APK build, unit suite (399 tests, zero failures/errors/skips) and
lint pass. Lint retains existing warnings and baseline findings. Debug APKs were
installed in place on the USB Pixel 10; installed hashes were compared with the
local artifacts. Notification-listener permission remains enabled. An in-place
install terminates the Android process, so its process-owned AACP socket closes
even while Bluetooth audio stays connected. The first restart path waited for a
later BLE status update; on this Pixel that took about two minutes. Startup now
queues an AACP reconnect as soon as the A2DP proxy confirms the saved AirPods are
still connected, while retaining the A2DP ownership and peer-cooldown gates. On
the 25 October USB install check, the new process restored AACP and received its
first frame about 1.2 seconds after service startup, then continued receiving
motion frames. The currently installed build uses a 275 nod threshold and a
two-repetition rule. The Pixel reconnected the AirPods control link after install.

USB-confirmed checks on the updated routing include locked Teams and WhatsApp
answer, incoming reject and connected-call hang-up; locked Viber answer/reject;
and cellular answer, connected-call end and incoming reject. WhatsApp connected
with music stopped, and the final timing guard skipped the fallback while its
notification was still incoming. Viber's earlier failed ALLOWED-mode attempt is
superseded by the confirmed ALLOW_ALWAYS-mode result.

Configured head gestures share answer/reject routes. Notification-only stage
transitions now start incoming gesture detection and stop it on connection/end;
late incoming gestures cannot dispatch an active-call toggle. Physical testing
found 250 too easy to trigger and 350 too difficult for this user. The installed
300 threshold with three repetitions passed one incoming nod-answer and one
shake-reject test. At the user's request, the detector now requires two
repetitions with the same amplitude thresholds. On the installed two-repetition
build, two deliberate nods answered a fresh Viber call at 0.90 confidence. A
two-shake reject check is still pending. Video, system-locale,
corporate/work-profile and concurrent physical-call checks are still incomplete;
the earlier pre-change video/locale observations below are evidence of app
contracts, not complete regression coverage for the final implementation.

On the 25 October post-sync physical Viber test, the notification was confirmed
INCOMING before movement. A single horizontal turning point during the still
period did not trigger an action. Three deliberate nods produced six alternating
vertical turning points and 0.97 confidence; the current notification's Answer
action was dispatched and Viber then posted ONGOING. An earlier run had already
reached ONGOING before the recorded head movement and is not counted as an
incoming-answer test; its active-call gestures toggled mute/unmute. On a second
fresh ringing call, one ordinary horizontal turn did not trigger an action. Three
clear left/right shakes produced six alternating horizontal turning points and
0.87 confidence; the current notification's Decline action was dispatched and
the incoming notification was removed. The user confirmed rejection.

The follow-up two-repetition build was tested on 25 October: Viber was confirmed
INCOMING, two nods generated an accepted vertical pattern (confidence 0.90), the
Answer action was dispatched, and the app posted ONGOING. The user confirmed it
connected. A subsequent build lowered only the nod amplitude threshold from 300
to 275 at the user's request; it is installed but this additional sensitivity
has not yet had a physical call validation. Rejection with two shakes remains to
be validated.

### WhatsApp in-call mute limitation

On 25 October, USB captures during an active WhatsApp call showed that its
ongoing CallStyle notification exposes only `Hang up`; it has no Mute/Unmute
action and no notification mute-state transition. After an AirPods stem press,
EveryPods logged `setMuted(...): no cached action`. The global
`AudioManager.setMicrophoneMute()` flag changed, but the user confirmed that
WhatsApp's in-call mute button did not follow it. This is not a missing label,
icon or translation: there is no WhatsApp mute action in the posted notification
for EveryPods to invoke.

The same live call appeared in Android Telecom as an active self-managed WhatsApp
call. EveryPods has `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, and
`MODIFY_AUDIO_SETTINGS`, but it does not declare an `InCallService`, is not the
default dialer, and is not registered as a call companion. The Pixel's default
dialer is Google Phone. Therefore the current app does not have the call-specific
Telecom control route for this WhatsApp call. Android's `InCallService` can
control calls made available to it, including eligible self-managed calls, but
EveryPods has not established that integration. Replacing the default dialer or
adding an accessibility-based workaround is outside this implementation.

This remains a documented limitation: WhatsApp in-call mute state is not
synchronized by the stem. The current system-wide microphone mute is not proof
that WhatsApp's own mute state changed. Teams, Viber and ordinary cellular mute
sync remain user-confirmed.

Relevant platform references:

- Android `AudioManager.setMicrophoneMute()` says it is intended for apps that
  replace platform-wide audio management or the main telephony app:
  https://developer.android.com/reference/android/media/AudioManager#setMicrophoneMute(boolean)
- Android `InCallService.setMuted()` controls microphone mute for calls visible
  to that in-call service:
  https://developer.android.com/reference/android/telecom/InCallService#setMuted(boolean)
- Android `METADATA_INCLUDE_SELF_MANAGED_CALLS` enables an eligible in-call
  service to receive self-managed calls:
  https://developer.android.com/reference/android/telecom/TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS

## Conclusion

The failure risk is broader than missing translations. Existing code can reinterpret
structured incoming calls as active calls, retain actions from another call, and
route a reject gesture through an operation that answers a ringing call.

Prioritize preserving authoritative call state and fixing action ownership/routing.
Then add verified app fallbacks. Do not implement one universal icon or translation
lookup: the three installed packages expose different information.

Package inspection establishes available code paths, not which server-controlled
path every incoming call will use. No incoming calls were placed or answered during the initial package investigation.
Subsequent personal-account Teams, WhatsApp and Viber USB captures are
documented in `voip-incoming-call-teams-usb-results.md`,
`voip-incoming-call-whatsapp-usb-results.md` and
`voip-incoming-call-viber-usb-results.md`. Corporate Teams remains untested;
Viber screen-answer transitions were confirmed in earlier captures. The latest
locked-call test did not answer through the AirPods stem; see the Viber results
for the Android background-launch restriction evidence and newer sender-mode fix.

## Evidence and method

Read Android documentation and local Android SDK 36.1 framework source. Inspected
APK resources, manifests and relevant DEX methods from the Pixel running API 37.
The Pixel became available over USB as `5C170DLCR00176` during research; subsequent
device snapshots used that connection. Earlier package copies used its existing
wireless ADB connection. No settings, app data or installed apps were changed.

| App | Installed version | Package evidence |
| --- | --- | --- |
| Teams | `1416/1.0.0.2026183002`, code `2026183025`, target 36 | Native-experiment flag selects CallStyle or custom incoming notifications. |
| WhatsApp | `2.26.41.77`, code `264107700`, target 36 | Actual notification builder calls a helper that constructs incoming and ongoing CallStyle notifications. Most resource entry names have been removed. |
| Viber | `29.1.1.0`, code `1290101`, target 36 | Concrete incoming/ongoing notification extenders call AndroidX CallStyle APIs. Resource names and translations remain available. |

Research files are outside the repository in `/tmp/everypods-voip-analysis/`.
They contain installed APKs, resource/manifest dumps, narrowly decompiled classes,
bytecode evidence and USB baseline dumps. Decompiled app code and full device dumps
must not be included in an implementation commit or PR. Some baseline device dumps
can contain private notification information.

## Android's actual contract

- CallStyle supplies separate answer/decline PendingIntents and an incoming call
  type, independently of displayed language. On Android 12+ the platform generates
  the standard buttons. The local framework writes `android.callType`,
  `android.answerIntent`, `android.declineIntent` and `android.hangUpIntent`.
- There is no dedicated public notification semantic action for answering or
  rejecting an incoming call. `SEMANTIC_ACTION_CALL` means calling a contact/group;
  it must not be interpreted as accepting a ringing call.
- A CallStyle incoming notification can have an additional custom action. Framework
  source inserts that action between the negative and answer buttons. Therefore
  `actions[1]` is not a reliable answer button even for an incoming CallStyle.
- `TelecomManager.isInCall()` includes dialing, ringing, active and holding calls.
  It does not establish that a call has been answered.
- `MediaController.dispatchMediaButtonEvent()` returning true only confirms that
  the event was sent to the media session. It does not confirm the call transition.
- AOSP Telecom answers a ringing call on a SHORT_PRESS headset event and uses a
  LONG_PRESS to reject it. Sending an end-call short press while actually ringing
  can therefore answer instead.
- Activity PendingIntents are subject to background launch rules. Finding the
  correct answer intent is separate from confirming that Android allowed it to run.
- Work profile policy can prevent notification listeners from receiving events.
  App notification delivery/battery restrictions can also prevent a usable event
  from reaching EveryPods. A label matcher cannot repair absent notifications.

## Package findings

### Teams

`CallNotificationUtilities.applyNotificationStyle(...)` reads
`enableCallStyleNotifications` with a default of false. Its structured branch fills
incoming call type, answer and decline intents. Its other branch uses a decorated
custom view and adds Answer first, Decline second. This is a concrete example where
assuming one universal action ordering would be wrong.

The custom branch references these actual resource keys:

- `notifications_pre_call_answer_audio`: English `Answer`, installed Spanish `Contestar`.
- `notifications_pre_call_decline`: English `Decline`, installed Spanish `Rechazar`.

Some incoming/pre-call answer intents launch activities. `CallingBroadcastReceiver`
also handles `answerAudioCall`, `answerVideoCall` and `declineCall`. These internal
intent names explain app behavior; ordinary EveryPods code cannot inspect the
contents of an arbitrary PendingIntent using a supported public API. Do not forge
those intents or depend on ADB-only introspection in the shipping implementation.

Plan: prefer actual CallStyle extras, then a Teams adapter using proven incoming
resource keys. Verify action icons on a real incoming call before mapping them.
Yesterday's verified mute icons do not establish incoming-call icon meanings.

### WhatsApp

The resource table has predominantly `(name removed)` entries. A Teams-style
`getIdentifier(resourceName, ...)` fallback cannot be assumed to work here.

The DEX class `X.EuP` has a notification helper `A09` that creates incoming CallStyle
with separate answer and decline intents and ongoing CallStyle with a hang-up
intent. The larger builder `A0D` invokes that helper; this invocation was checked in
bytecode because the decompiler could not reconstruct the large method fully.

The incoming answer helper creates an activity intent, including ACCEPT_CALL or
join-call variants. The decline helper selects a broadcast receiver or foreground
service according to app configuration. The manifest declares a self-managed
Telecom ConnectionService and Jetpack telecom components. This shows integration
exists, but does not prove which integration every call uses at runtime.

Plan: make structured notification support correct first. Do not hardcode numeric
resource IDs, obfuscated class names or an assumed resource-name adapter. Investigate
any real legacy notification separately if it is encountered.

### Viber

The concrete incoming extender `dru.extend` calls
`NotificationCompat.CallStyle.forIncomingCall(person, declineIntent, answerIntent)`.
The ongoing extender `r820.extend` calls `forOngoingCall(...)`. These are actual app
extenders, beyond merely finding a bundled AndroidX library.

Its resource table retains `call_notification_answer_action`,
`call_notification_answer_video_action` and `call_notification_decline_action`.
The answer/decline resources inspected are part of the AndroidX CallStyle resources;
this alone does not prove they are labels on a separate Viber legacy notification.

Actual packaged values include Portuguese `Atender` / `Recusar`, Ukrainian
`Відповісти` / `Відхилити`, and Vietnamese `Trả lời` / `Từ chối`.

Plan: use structured intents first. Resource-derived fallback is possible, but
prove a legacy builder's resource usage or capture its notification before selecting
keys or icon mappings. Do not generalize Viber behavior from Teams.

## Confirmed problems in EveryPods code

### 1. Weaker label guesses can corrupt structured call state

`CallNotifListener.handle` resolves CallStyle intents, then still classifies every
action using keyword substrings. `HANG_UP_KEYWORDS` includes the bare substring
`end` and runs before answer matching. The real packaged Portuguese label
`Atender` contains `end`. It can consequently set `foundHangUp` on the answer button.

The final `ringingNow` expression rejects ringing whenever any hang-up/mute signal
was found. Thus an explicit structured incoming call can become an active call
because of a weaker translated-label match. A custom Silence/Mute action on a
ringing notification presents a similar state-classification risk.

### 2. The positional fallback can choose a custom action

With incoming call type and no resolved answer/decline, the code assigns action 0
to decline and action 1 to answer whenever at least two actions exist. The Android
framework permits additional actions between those buttons. Remove this inference
rather than widening it to more apps.

### 3. The action cache can mix calls and apps

The listener stores one shared set of action variables. Updates use
`newAction ?: oldAction`, including across different notification keys and packages.
This retains old answer/decline/hang-up actions. Removal only clears the currently
tracked key. A notification transition or another app's call can inherit an action
that no longer belongs to the current call.

### 4. Reject routing can disagree with the stem handler

`handleCallStemPress` considers notification ringing and other VoIP evidence.
`rejectCall`, however, defines ringing only from `isCallRinging` and infers active
from `isInCall || isVoIPCallActive || telecomInCall`.

For a VoIP incoming call with notification ringing true, telephony ringing false
and Telecom isInCall true, the stem handler chooses reject, but rejectCall enters
its active-call headset-short-press route. Android can answer the call instead.
The notification fallback also tries hang-up before decline regardless of state,
which becomes worse if a stale hang-up action exists.

### 5. Dispatch is reported as completion

`answerCall` returns after a successful media-event dispatch without checking that
the incoming call became active. PendingIntent sends similarly establish dispatch,
not completion. Blindly retrying a headset toggle is also unsafe: the first event
might have answered, and a second event could then hang up.

### 6. Additional gaps

The legacy keyword fallback lacks Ukrainian and Vietnamese. A notification-only
ringing event currently triggers stem setup, while telephony ringing also starts
the incoming head-gesture handling path; implementation should explicitly decide
whether notification-backed ringing must support the same configured gesture flow.
AUDIO_MODE_IN_COMMUNICATION is supporting evidence, not a per-call state or identity.

## Proposed implementation, in order

1. **Create a resolver with explicit evidence priority.** Produce an immutable
   notification snapshot with package, user/profile, key, stage (incoming, ongoing,
   screening or unknown), available actions and recognition source. CallStyle
   intent identity and explicit type outrank icons, resources and labels. A call's
   stage and action availability are separate: an incoming call can be known even
   if its actionable intent is unavailable. Lower-priority evidence cannot replace
   an authoritative assignment. Screening is not silently treated as incoming.
2. **Replace the shared cache with snapshots per notification.** Updates replace
   a snapshot atomically; removals delete only that owner. Ringing-to-active
   transitions discard obsolete answer/reject actions. Derive aggregate flags from
   current snapshots. Use a deterministic call target for a gesture; do not use an
   arbitrary cached action when multiple calls make the target ambiguous.
3. **Fix routing from a common call context.** Pass the resolved target and stage
   through stem and configured head-gesture handling. For a known incoming VoIP
   notification, use its explicit answer/decline action before generic toggles.
   Active-call hang-up must never be selected merely because Telecom isInCall is
   true. Keep cellular handling and supported Telecom fallbacks distinct from
   notification-backed actions.
4. **Add only evidenced app adapters.** Start with Teams' verified incoming string
   keys, loading actual app resources for its current locale plus EveryPods'
   supported locales. Resolve resource owners and add narrowly scoped package
   visibility where required. Handle missing language splits/resources gracefully.
   Prefer CallStyle for WhatsApp and Viber. Record unknown notification shapes for
   diagnosis rather than guessing resource names, translations or action order.
   Exact known labels should not use an unbounded `end` substring.
5. **Observe transitions without blindly replaying toggles.** Log dispatched,
   confirmed and failed/unknown separately. Link commands to a snapshot generation.
   Avoid duplicate actions while one transition is pending; invalidate requests on
   call replacement/end. Do not automatically resend an answer/end headset toggle
   solely because an app notification update was delayed.
6. **Verify before installation/release.** Use meaningful resolver and routing
   regression coverage, then USB captures and real incoming calls for each app.
   Keep proprietary decompilation artifacts outside the repository.

## Required verification before release

- Structured incoming calls with unknown titles, including Portuguese `Atender`.
- Ukrainian/Vietnamese resource labels, without assuming handwritten translations.
- Incoming CallStyle with a third/custom action, absent labels and conflicting
  weak clues; never infer the answer intent by array index.
- Unknown or partially available actions; do not mark dispatch as completion.
- Incoming -> active -> ended, missed/declined calls, listener reconnect and
  cancelled PendingIntents. No previous app/call action may survive reassignment.
- Two apps posting notifications concurrently, unrelated message notifications,
  and ringing while another call is active/held.
- Both stem answer/reject and configured incoming-call head gestures.
- Real Teams, WhatsApp and Viber incoming voice calls, locked and unlocked screen,
  while idle and while media is playing. Include video-call answer semantics and
  background activity restrictions where applicable.
- USB confirmation that an incoming reject never answers the call, and that answer
  does not accidentally enable video or terminate another call.

## Sources

Primary documentation is sufficient for the platform rules; forums were not used
to establish app internals. Public support pages explain user-facing controls and
notification prerequisites but do not specify private resource identifiers or a
stable third-party incoming-call API. Those details were investigated in the
installed packages and remain version-dependent.

- Android CallStyle:
  https://developer.android.com/reference/android/app/Notification.CallStyle
- Android call notification guide:
  https://developer.android.com/develop/ui/compose/notifications/call-style
- Android notification action semantics:
  https://developer.android.com/reference/android/app/Notification.Action
- Android media dispatch result:
  https://developer.android.com/reference/android/media/session/MediaController
- Android Telecom API:
  https://developer.android.com/reference/android/telecom/TelecomManager
- AOSP headset-event interpretation, `CallsManager.onMediaButton`:
  https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/CallsManager.java
- Background activity launch rules:
  https://developer.android.com/guide/components/activities/secure-bal
- Notification listener restrictions:
  https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Teams answering calls:
  https://support.microsoft.com/en-us/teams/calls-devices/answer-a-call-in-microsoft-teams
- WhatsApp voice calls (Android):
  https://faq.whatsapp.com/1153602608602452/?cms_platform=android
- Viber notification prerequisites:
  https://help.viber.com/hc/en-us/articles/9045307304861-Fix-notification-issues

Local framework sources consulted:
`/Users/nikos/Library/Android/sdk/sources/android-36.1/android/app/Notification.java`,
`android/telecom/TelecomManager.java`, `android/media/session/MediaController.java`,
and `android/service/notification/NotificationListenerService.java` under that tree.
