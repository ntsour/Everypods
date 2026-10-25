# WhatsApp incoming calls: Pixel USB observations

## Current implementation validation: 25 October 2026

The first test on the new notification-action route exposed a platform block.
WhatsApp's structured Answer action is an Activity PendingIntent. Pixel Android
reported `Background activity launch blocked` for
`com.whatsapp/.calling.ui.VoipActivityV2` when EveryPods sent it from the
background service. EveryPods saw the call remain incoming; the user confirmed
it did not answer, and WhatsApp removed it as a missed call.

The build was updated with a delayed, guarded fallback. On a repeat incoming
audio call, one stem press first attempted WhatsApp's notification action. At
800 ms the same notification still reported incoming and was the only
notification-backed call, so EveryPods dispatched Telecom's HEADSETHOOK event.
Telecom moved to OFFHOOK, WhatsApp replaced the incoming CallStyle with an
ongoing notification containing Hang up, and the user confirmed the call
connected. A later double press dispatched that ongoing notification's Hang up
action and returned Telecom to IDLE; the user confirmed the call had ended.

This validates both the BAL-block diagnosis and the fallback on this device.
Multi-call safety continues to rely on declining the fallback when more than one
notification-backed call is present; concurrent calls were not part of this
test.

### API 36 sender-mode update and transition race

With ALLOW_ALWAYS enabled and the Pixel locked, a user test confirmed one stem
press connected WhatsApp and stopped music. At 09:01:33.366 Android permitted
the direct Answer activity (`BAL_ALLOW_SAW_PERMISSION`). OFFHOOK arrived at
09:01:34.086, but the notification was still incoming at the 800 ms fallback
deadline. The fallback sent a Telecom headset command at 09:01:34.357 just before
the ongoing notification arrived. This test therefore exposed a race rather
than establishing a clean final answer path: the fallback must stop on system
or audio active-call evidence as well as a notification-stage change. The new
guard and a regression test cover that ordering; a repeat physical call is
required after installation.

### Guarded build: confirmed locked-call cycle

At 09:05:14.885 the direct notification Answer was dispatched. OFFHOOK arrived
at 09:05:15.633; at 09:05:15.857 EveryPods logged that system/audio already showed
an active call and skipped the fallback, while the notification was still
incoming. WhatsApp posted the ongoing notification at 09:05:16.022. The user
confirmed that the call stayed connected and music stopped. At 09:05:45.321 a
double stem press dispatched the ongoing Hang up action, and IDLE followed.
At 09:06:04.927 an incoming double press dispatched Decline; the notification
was removed and IDLE followed without OFFHOOK. The user confirmed both outcomes.

## Earlier implementation investigations

24 October 2026, Europe/Madrid. Pixel 10 over USB. Data collection only;
production code, app language and device settings were not changed.

Raw evidence (private, outside Git):
`/tmp/everypods-voip-analysis/whatsapp-live/`.
Notification and Telecom dumps can contain private account/contact information;
keep them outside commits and PRs.

## Observed notification contract

Incoming voice call: native CallStyle, `android.callType=1`,
`android.callIsVideo=false`, separate Answer Activity PendingIntent and Decline
Broadcast PendingIntent. EveryPods identified ringing using structured extras.
The preliminary notification had no actions; it was replaced by the actionable
incoming notification.

After answering on the phone, the same main notification key transitioned to
CallStyle `android.callType=2`. Answer and Decline extras became null; a Hang up
Broadcast PendingIntent became available. Android Telecom moved to active and
EveryPods changed its notification flags from ringing to active.

Incoming video call: CallStyle `android.callType=1`, `android.callIsVideo=true`.
Buttons were **Decline** and **Video**, not Answer. EveryPods correctly resolved
Video as Answer through the structured intent. This directly demonstrates why
button labels cannot be required for detection or action identity.

## User tests and log evidence

| Case | Result and route |
| --- | --- |
| Screen answer/end baseline | Answer on screen confirmed by user, OFFHOOK at 19:52:32, ongoing CallStyle followed; screen end produced IDLE at 19:52:50. |
| AirPods answer | User confirmed answered; at 19:53:32 single press dispatched HEADSETHOOK to Android Telecom, followed by OFFHOOK. The notification Activity PendingIntent was not invoked. |
| AirPods reject | User confirmed rejected; at 19:54:05 ringing flags were true, TelecomManager.endCall returned false, then notification Decline was fired and IDLE followed. |
| Locked screen with media scenario | User arranged media playback/lock and confirmed locked ringing, then confirmed AirPods answer. Window policy snapshot showed keyguard showing. At 19:55:49 single press dispatched through Telecom, followed by OFFHOOK. Media-session snapshots were captured, but the user did not separately confirm media pause/resume behavior. |
| Incoming video, AirPods answer | User confirmed answered with video. At 19:56:43 single press dispatched through Telecom, followed by OFFHOOK. Camera and Telecom snapshots were saved. Existing video-enabled answer behavior must be accounted for. |

Additional call activity occurred during setup around 19:55:05–19:55:13. It should
not be conflated with the explicitly prompted locked ringing/answer sequence
starting at 19:55:18. The continuous log preserves both sequences.

## Implementation implications

- Prefer authoritative call type and intent extras for WhatsApp. No resource-name
  adapter or translated answer label was needed in these cases.
- Updates now replace per-key action snapshots atomically when the notification
  changes from incoming to ongoing; this lifecycle issue is covered by the
  implementation update in `voip-incoming-call-analysis.md`.
- Treat preliminary no-action notifications as incomplete evidence, not an excuse
  to infer array positions or invent answer/reject actions.
- Preserve notification Decline: it was the working rejection route after the
  generic Telecom endCall request returned false.
- Successful Telecom answer does not validate direct notification Activity
  PendingIntent dispatch or its locked-screen background-launch behavior.
- Both telephony and notification ringing flags were available for these stem
  attempts. The notification-only rejection risk remains untested.

## Remaining scope

Viber, corporate Teams, actual system-language Portuguese/Ukrainian, call waiting,
multiple app calls, listener reconnect, cancelled intents, configured head gestures,
and direct Activity PendingIntent answer dispatch remain unverified.

See `voip-incoming-call-analysis.md` and `voip-incoming-call-teams-usb-results.md`.

## WhatsApp in-call mute sync limitation: 25 October 2026

During a live WhatsApp audio call, USB notification inspection showed a single
ongoing CallStyle action, `Hang up`. The notification's extras contained no mute
intent or mute-state field. Tapping WhatsApp's on-screen microphone control did
not add a notification action or cause an observable notification mute-state
transition.

After an AirPods stem press, EveryPods logged the Android microphone mute flag
changing (`setMicrophoneMute(true)`); `CallNotifListener.setMuted(true)` then
reported `no cached action`. A later toggle logged the inverse state and the same
missing-action result. The user confirmed the WhatsApp microphone button did not
change. This proves that the current path changes Android's global microphone
mute flag but does not synchronize WhatsApp's own in-call control. It does not
establish whether the remote caller heard silence during the brief system mute.

The live call was present in `dumpsys telecom` as ACTIVE with `PROPERTY_SELF_MANAGED`.
EveryPods declares `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS` and
`MODIFY_AUDIO_SETTINGS`, but has no `InCallService` and is not the default dialer;
the Pixel's default dialer is Google Phone. The current APK is not registered as
a call companion. Notification action/label/icon resolution cannot fix this
because WhatsApp posts no mute action for EveryPods to invoke.

This is a known limitation. Accessibility-based control is explicitly excluded.
An eligible Telecom companion integration could be investigated separately, but
EveryPods has not established access to WhatsApp's self-managed call through
that API. Platform references and the scope boundary are recorded in
`voip-incoming-call-analysis.md`.
