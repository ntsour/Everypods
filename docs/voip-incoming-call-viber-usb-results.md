# Viber incoming call: USB capture results

Capture dates: 24 and 25 October 2026. Pixel 10, Viber `29.1.1.0`, package
`com.viber.voip`. Test used an incoming audio call while the phone was unlocked.
No implementation changes were made for this capture.

## Incoming notification

The call notification was Android `Notification.CallStyle` with
`android.callType=1` (incoming), an explicit `android.answerIntent`, and an
explicit `android.declineIntent`. The platform actions were `Decline` and
`Answer`; Android represented Decline as a service PendingIntent and Answer as
an activity PendingIntent. There was no hang-up intent on the incoming
notification. EveryPods recognized the notification from its CallStyle data and
set its VoIP-notification ringing flag.

At that moment, the EveryPods log reported `isCallRinging=false`,
`isInCall=false`, `isVoIPCallActive=false`, `audioMode=0`, and
`telecomInCall=false`, despite the incoming Viber notification. This is a live
example where notification state supplied the ringing signal used by the stem
handler.

## Connected-call transition

A second USB session captured the full screen-answer transition. At the incoming
stage, Viber again posted CallStyle type 1 with Answer and Decline actions;
EveryPods showed notification ringing while telephony and Telecom state flags
were false. After the user answered on screen, the notification changed to
CallStyle type 2 with `Hang up` and `Mute`, and Android audio mode changed to
`IN_COMMUNICATION`. EveryPods then recognized Viber as active. Ending the call
returned audio mode to normal and cleared the active-call notification state.
This confirms that the answer transition is observable through notification
replacement plus audio mode, without a Telecom call-state callback.

## Stem actions and limitation

The first capture's AirPods single-press event invoked
`TelecomManager.acceptRingingCall()` and fired Viber's explicit Answer action,
but did not confirm the outcome before the call was declined. In a later
controlled test, one AirPods single press again dispatched the Telecom answer
operation and Viber Answer PendingIntent. This time the logs then showed audio
mode changing to `IN_COMMUNICATION` and Viber replacing the incoming
notification with its ongoing `Hang up`/`Mute` CallStyle. The call therefore
successfully answered through the AirPods stem.

A subsequent double-press event in the first capture ran the reject path. `TelecomManager.endCall()`
returned false, there was no cached hang-up action, and the listener fired
Viber's explicit Decline action. The incoming call notification was removed and
a missed-call notification appeared. The final decline is observed; the
success of the preceding answer attempt in that first capture remains unknown;
the later on-screen and AirPods tests both confirmed active-call transitions.

The reconnect session also recorded a second brief Viber incoming notification
after the confirmed connected call ended. Its short audio-mode transition was
not part of the prompted answer test and is not used as evidence about answer
or reject behavior.

Treat PendingIntent dispatch as an attempted action, not proof of a call-state
transition. This capture also shows why each action must remain associated with
its incoming notification and why ringing-stage routing should prefer that
notification's Decline action over a generic hang-up operation.

## Locked-call validation on the updated build

On 25 October, the updated EveryPods build detected an incoming Viber CallStyle
notification while the Pixel was locked. A single AirPods press dispatched its
Answer PendingIntent, but Android blocked Viber's private
`com.viber.voip/.phone.PhoneFragmentActivity` launch from the background
(`BAL_BLOCK`). No Telecom headset session was available. The guarded
`TelecomManager.acceptRingingCall()` request ran, but Viber remained in the
incoming-notification state; the caller then stopped ringing and Viber posted a
missed-call notification. The user confirmed that the call did not answer.

This establishes a failure of the tested launch options for locked Viber calls:
an explicit notification Answer action is not sufficient when Android blocks
its activity, and the privileged Telecom fallback does not accept this Viber call. Do not
report a successful answer based on dispatch or API invocation. The fallback
was removed; the separate Telecom headset-session route remains for apps such
as WhatsApp where it produced a confirmed active-call transition. Locked Viber
decline was confirmed by the user on an earlier build; a repeat on this exact
updated build remains useful but is not required to explain the answer failure.

### Supported launch exemption: confirmed working

Further inspection found that EveryPods already has user-granted
`SYSTEM_ALERT_WINDOW` on this Pixel. Android's current
`BackgroundActivityStartController.checkBackgroundActivityStartAllowedByRealCallerInBackground`
requires `MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS` before checking this
sender permission. The old `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` mode does
not pass that check. Android documents ALLOW_ALWAYS (API 36+) for companion apps
responding to connected-device prompts. Call-action dispatch now uses it on
API 36+, retaining ALLOWED on API 34–35. This transfers only existing launch
privileges; it does not grant overlay permission.

On the updated build, the Pixel was confirmed locked (`deviceLocked=1`). At
08:59:50.946 a single stem press launched Viber's ACCEPT_CALL action with
`BAL_ALLOW_SAW_PERMISSION [realCaller]` and result code 0. Android changed audio
mode to IN_COMMUNICATION and Viber posted its ongoing Hang up/Mute notification;
the user confirmed connection. A double press at 08:59:59 dispatched that
ongoing Hang up action and cleared it. On a second call, a double press at
09:00:22.943 dispatched Decline, removed the incoming notification, and produced
a missed-call notification; the user confirmed rejection. The newer sender mode
fixes the reproduced locked-call answer failure on this Pixel with its existing
overlay permission. Devices without a background-launch exemption may still
block an Activity Answer PendingIntent.

Sources: [ActivityOptions reference](https://developer.android.com/reference/android/app/ActivityOptions#MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS),
[AOSP background activity controller](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/BackgroundActivityStartController.java).

## Artifacts

Sanitized observations are summarized here. Full notification, Telecom and
audio dumps plus the continuous app log are held outside the repository under
`/tmp/everypods-voip-analysis/viber-live/`; they can contain private call and
notification details and must not be committed.
