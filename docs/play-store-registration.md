# Google Play Registration Pack

This is the current Play Console input sheet for EveryPods. Values marked
`[DECIDE]` need an account-owner decision before submission.

## App Identity

- App name: `EveryPods`
- Application ID: `io.automated.ventures.everypods`
- App type: App
- Category: Tools
- Distribution: Google Play Android phones and tablets
- Minimum Android version: Android 13 / API 33
- Target API: 37
- Current public version: `1.0.0` / version code `100`
- Developer contact email: `automated.ventures.apps@gmail.com`
- Support email: `automated.ventures.apps@gmail.com`
- Support repository: `https://github.com/ntsour/Everypods`
- Privacy policy URL: `https://ntsour.github.io/Everypods/privacy/`

## Store Listing

Short description, under 80 characters:

> Seamless AirPods handover and smart controls across your devices.

Full description:

> Keep your AirPods moving with you. EveryPods brings seamless multi-device
> handover to Android, so you can move your AirPods between devices running
> EveryPods without breaking your flow—no root required.
>
> Beyond handover, EveryPods adds smart features built for everyday use:
>
> - Smart mute and unmute controls for calls.
> - Gym Mode for a better workout experience.
> - Find your AirPods when they are nearby.
> - View AirPods and charging-case battery levels.
>
> Make your AirPods your own with powerful controls and configuration:
>
> - Switch listening modes, including noise cancellation and transparency.
> - Configure stem gestures, press actions, volume controls, and ear detection.
> - Use widgets, Quick Settings, and an optional connection overlay.
> - Customize call controls, notification announcements, head tracking, and
>   other supported device features.
>
> Feature availability depends on the AirPods model, firmware, Android version,
> and the phone manufacturer's Bluetooth implementation. AirPods Pro 2 is the
> primary tested model. Other AirPods models may expose fewer controls.
>
> EveryPods is an independent open-source project and is not affiliated with
> or endorsed by Apple. AirPods is a trademark of Apple Inc.

## Listing Assets

- App icon: use the generated EveryPods launcher icon from the Android bundle.
- Phone screenshots: use the files in `android/imgs/`; select 4 to 8 clear
  screenshots showing connection, battery, listening modes, settings, and
  support.
- Feature graphic: `[DECIDE]` create a Play Console 1024 x 500 feature graphic.
  The repository banner is 2560 x 1280 and is not automatically the correct
  Play feature-graphic size.
- Promo video: none.

Do not upload screenshots containing Bluetooth addresses, personal contacts,
private notifications, account data, or development/debug labels.

## App Content Answers

These are the intended answers; confirm them against the exact uploaded build.

- Ads: No ads.
- In-app purchases: No. All features are available without payment. Donations
  are optional and are not processed inside the app at this time.
- Target audience: Adults and general users; not designed for children.
- Designed for families: No.
- News app: No.
- Government app: No.
- COVID-19 contact tracing/status app: No.
- Financial features: No.
- Health features: No medical diagnosis or treatment claims.
- User-generated content: No public user-generated content.
- App access: No account required. Core features are available after the
  user grants the relevant Android permissions and connects compatible AirPods.

## Data Safety Draft

The current Android manifest includes Internet access only for the optional,
user-enabled ElevenLabs text-to-speech feature. The app contains no analytics
or advertising SDK. Complete the Google Play Data Safety form based on the
exact release build and the following behavior:

- Does the app collect data? Only when a user enables ElevenLabs cloud
  text-to-speech. The user's announcement text, selected voice/language, and
  their own API key are then transmitted directly to ElevenLabs.
- Does the app share data with third parties? Yes, with ElevenLabs for that
  opt-in text-to-speech processing. EveryPods does not operate a proxy or
  receive a copy of the transmitted data.
- Is data encrypted in transit? The ElevenLabs integration uses HTTPS; verify
  the exact provider disclosure requirements before completing the form.
- Can users request deletion of data? Local app data can be deleted by
  uninstalling the app or clearing its storage.
- Data stored locally: Bluetooth/device identifiers, connection state, feature
  preferences, and local diagnostic state.
- External handoffs: Support email and GitHub links open the user's selected
  email/browser app. Anything the user sends there is handled by that service,
  not by EveryPods.
- Optional cloud text-to-speech: enabled users' announcement text, selected
  voice/language, and their own ElevenLabs API key are sent directly to
  ElevenLabs. This must be disclosed in Data Safety and the privacy policy.
- Donations: No donation payment flow is currently included in the app.

Review this form again if Internet access, analytics, crash reporting, cloud
sync, or another SDK is added.

## Permission Declaration Notes

The app requests permissions for optional features. In Play Console, explain
each permission in terms of the user-facing feature and only retain permissions
that are essential to the declared features:

- Bluetooth connect/scan/advertise: discover, connect to, and control AirPods.
- Foreground connected-device service: maintain an active AirPods connection.
- Notifications: show battery, connection, and announcement notifications.
- Phone state and answer calls: optional AirPods call controls.
- Call log and contacts: optional notification-announcement filtering and
  caller/context features. These are sensitive and may require a Play
  permissions declaration or removal if Play does not accept the use case.
- Notification listener: optional notification announcements and call controls.
- Accessibility service: optional camera controls triggered by AirPods gestures.
- Overlay: optional connection popup/overlay.
- Ignore battery optimizations: optional reliable background connection.
- Boot completed: restore the optional automatic connection behavior.

The `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_NUMBERS`, and
`ANSWER_PHONE_CALLS` declarations are the highest-risk submission items. Verify
their necessity and Play policy eligibility before uploading the production
bundle.

## Release Checklist

1. Create the app in Play Console using the exact application ID
   `io.automated.ventures.everypods`.
2. Enter the published privacy-policy URL in the Store Listing and App Content
   sections.
3. Configure Play App Signing and retain the upload key securely.
4. Configure the protected `play-release` environment secrets in GitHub.
   Keep `main` branch protection enabled: one approving review, resolved
   conversations, linear history, no force pushes or deletion, and required
   Android CI, CodeQL, and dependency-review checks.
5. Run the manual Play release workflow and upload the signed AAB. Do not upload
   the locally debug-signed AAB produced without release secrets.
6. Complete Data Safety, content rating, target audience, ads, app access, and
   sensitive-permission declarations.
7. Run internal testing first. If this is a newly created personal developer
   account, plan for a closed test with at least 12 opted-in testers for 14
   continuous days before requesting production access.
8. Test startup, Bluetooth permissions, connection/reconnect, notifications,
   support links, donation messaging, backup behavior, and optional
   special-access flows.
9. Submit for review only after the Play pre-launch report and policy alerts
    are clear.

## Account-Owner Decisions

- Developer account type: `[DECIDE: personal or organization]`
- Public developer name: `[DECIDE]`
- Public developer address/contact details: `[DECIDE]`
- Stable privacy policy URL: `https://ntsour.github.io/Everypods/privacy/`
- Play feature graphic: `[DECIDE]`
- Countries/regions: `[DECIDE]`
- Free availability and advanced-feature price: `[DECIDE]`
- Whether to keep or remove call-log/contact permissions before submission:
  `[DECIDE]`
