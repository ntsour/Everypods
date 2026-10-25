# Teams incoming calls: Pixel USB observations

## New implementation validation: 25 October 2026

After installing the current branch APK on Pixel 10 (`1.0.4-rc19-debug`), the
user placed two personal-account Teams calls while USB log capture was active.
On the first call, Teams posted a CallStyle incoming notification with explicit
Answer and Decline actions. One AirPods single press dispatched the notification
Answer action; the incoming notification was removed, an ongoing notification
with Hang up/Mute appeared, Telecom moved to OFFHOOK, and audio mode became
`IN_COMMUNICATION`. The user confirmed the call connected. A subsequent double
press on the connected call dispatched Teams' Hang up action and reached IDLE.

On the second call, the incoming notification again provided an explicit
Decline action. An AirPods double press dispatched that action; the notification
was removed and Telecom reached IDLE without an OFFHOOK transition. The user
confirmed the call was rejected. This validates the new notification-owned
answer and reject routes on the installed build and confirms that the reject
gesture did not answer the ringing call.

### Locked-call regression on the guarded route

The user confirmed a locked Teams audio call answered and stayed connected.
The installed route skipped the delayed Telecom fallback when system/audio
already reported an active call. A later double press at 09:06:42.432 dispatched
the current Teams Hang up action, followed by IDLE. A second incoming call was
rejected at 09:07:11.887 through Teams' explicit Decline action; IDLE followed
without a connection. The user confirmed rejection. Additional unsolicited
answer/mute/end cycles were captured but are not used as proof of microphone
behavior without a corresponding user check.

## Earlier implementation investigations

Date: 24 October 2026, Europe/Madrid.
Receiving account: Teams personal/free. These observations do not establish corporate
account behavior. Pixel 10 connected over USB; EveryPods reported version
`1.0.4-rc19-debug`, installed at 19:09:35. No production code was changed for this
capture session. EveryPods had been rebuilt by other ongoing work since yesterday's
Relay/DirCon test; this document describes the actual installed debug build.

Raw evidence (private, outside Git):
`/tmp/everypods-voip-analysis/teams-live/`.
Includes the continuous `call-log.txt` and notification, Telecom, audio, media-session,
window-policy and camera snapshots at the named stages. Device dumps may contain
private notification/account information; do not commit them.

## Confirmed observations

| Case | Observed result | Actual route/evidence |
| --- | --- | --- |
| Unlocked incoming audio call | EveryPods recognized ringing. | Incoming notification had native `Notification.CallStyle`, `android.callType=1`, separate `android.answerIntent` and `android.declineIntent`. Both action semantics were 0. Answer was an Activity PendingIntent; Decline was a Broadcast PendingIntent. Icons resolved to `ic_call_answer` and `ic_call_decline`. |
| Answer on phone screen | Call became active. | Telecom snapshot showed ACTIVE and audio changed from MODE_RINGTONE to MODE_IN_COMMUNICATION. Ringing CallStyle notification was removed and replaced with a custom ongoing notification containing Mute and Hang up; that notification had no call-type or answer/decline extras. |
| End on phone screen | Call ended and call notification was removed. | Ended Telecom snapshot contained no current call. |
| AirPods single-press answer | User confirmed success. | At 19:41:31, ringing flags were true. EveryPods dispatched HEADSETHOOK to the Telecom HeadsetMediaButton session; OFFHOOK followed. Notification Answer intent was not used. |
| AirPods active-call hang-up | An active call ended. | At 19:42:01, double press dispatched through Telecom and IDLE followed. This was captured when the user ended the preceding call. |
| AirPods double-press reject | User confirmed rejection. | At 19:42:20, telephony and notification ringing flags were both true. TelecomManager.endCall returned false, then EveryPods fired notification Decline; IDLE followed. The call did not pass through the active-call headset-toggle branch. |
| Locked screen, single-press answer | User confirmed answering while locked. | Ringing notification was captured under keyguard; at 19:43:11 a stem press dispatched through Telecom, followed by OFFHOOK. This did not exercise the Activity PendingIntent background-launch path. |
| Incoming audio call during media playback | User confirmed answer. | At 19:44:07, stem press dispatched through Telecom; OFFHOOK followed. Pocket Casts and Spotify sessions both reported PAUSED in the connected-call snapshot. The snapshot alone does not establish which app was playing immediately before ringing. |
| Portuguese Teams app with English Android | Incoming buttons remained English; active controls were Portuguese. | User reported Teams UI Portuguese but incoming notification English. Logs at 19:45 showed Decline/Answer, while ongoing controls were Sem som/Desligar. This demonstrates different locale sources for incoming system buttons and app-owned controls. |
| Incoming video call, AirPods single press | User confirmed connected and Pixel camera on. | At 19:47:04, EveryPods dispatched HEADSETHOOK through Telecom; OFFHOOK followed. This is the observed existing video-answer behavior, not evidence that the camera stays off. |

## Call identities and lifecycle

Teams registered these incoming calls with Android Telecom through
`com.microsoft.skype.teams.calling.telecom.TelecomConnectionService`.
The ringing capture explicitly showed state RINGING. Therefore the existing comment
that Teams universally bypasses Telecom is not accurate for these personal-account
calls on this Pixel.

Teams replaced preliminary incoming notification IDs with another notification
before the call was answered. The listener cleared its cache on removal and then
recognized the replacement. This is evidence that notification removal alone does
not always mean the call ended, and snapshot/lifecycle handling should accommodate
notification replacement without retaining unrelated old actions.

## Language settings

Initial Teams per-app locale list was empty (follow device settings).
Android device locale list was `en-ES,es-ES,el-GR`.
Only Teams' app locale override was temporarily set to `pt-PT`; the device languages
were not changed. After the test the original empty per-app locale list was restored
and confirmed using `cmd locale get-app-locales`.

The Portuguese app override did not exercise the Atender substring bug because the
incoming CallStyle buttons remained English. Do not report that bug as reproduced
on-device. Its static code/package evidence is recorded in the analysis document.

## Implications for implementation

1. Preserve authoritative CallStyle type/intent assignments. The current personal
   calls supply all information needed to detect ringing without reading labels.
2. Derive notification-label locale from the actual source. Teams app language is
   not sufficient to predict system-generated CallStyle button text.
3. Keep notification-backed Decline available: this test used it successfully after
   the generic Telecom endCall request failed.
4. Preserve supported Telecom answering, while distinguishing dispatched events
   from confirmed state transitions. Single-press successes did not validate direct
   invocation of the notification's Activity PendingIntent.
5. Handle incoming notification replacement and the change to a custom ongoing
   notification; do not equate every removal with call termination.
6. Do not change video-answer camera behavior unintentionally. Current behavior
   here enabled the camera. Any different policy would need a separate decision.

## Still unverified

- Corporate-account incoming call shape and control routes.
- System-language Portuguese or Ukrainian incoming buttons; the per-app locale
  experiment did not change the system-generated incoming controls.
- Direct notification Answer Activity PendingIntent invocation from EveryPods,
  including background-launch restrictions with the screen locked.
- A VoIP incoming call with notification ringing true but telephony ringing false;
  the suspected reject/answer routing defect did not occur in these tests because
  both ringing signals were available.
- WhatsApp and Viber live behavior; multiple apps/call waiting; configured head
  gestures; cancelled actions and reconnect/replacement races.

See `voip-incoming-call-analysis.md` for the static package evidence and proposed fix.
