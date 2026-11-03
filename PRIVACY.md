# EveryPods Privacy Policy

**Last updated: November 3, 2026**

EveryPods is an Android app for compatible AirPods controls. It is designed to
keep its settings and connection state on your device. This policy explains the
data the app handles and the limited circumstances in which data leaves your
device.

## Data handled on your device

EveryPods stores Bluetooth device identifiers and connection state, feature
preferences, local diagnostic information, and—if you configure it—an optional
ElevenLabs API key and text-to-speech preference. This information is stored
locally on your Android device so the app can work.

EveryPods does not include advertising or analytics SDKs, and it does not sell
your personal data.

## Location and Gym automation

EveryPods can access precise and approximate location with your permission.
Location permission can also be used for Bluetooth discovery of nearby devices.
The optional Gym automation uses precise location to detect when you arrive at
or leave places you choose, so it can start or stop Gym Mode and its configured
actions. If you enable it and grant background location, this detection works
even when the app is closed or not in use. EveryPods uses Android and Google
Play services location and geofencing for this feature, including an occasional
current-location check to restore the correct state after a restart or reconnection.

Your saved gym names, locations and radii, and a small record of the current
visit state are stored on your device. EveryPods does not send your saved gym
coordinates or location history to its developer. You can disable Gym automation,
delete saved locations, or revoke location permissions in Android settings;
clearing app storage or uninstalling removes the locally stored data.

If you open the optional map picker, map tiles are requested from OpenStreetMap.
The tile provider receives your device IP address and the map area viewed, from
which an approximate location may be inferred. The selected point is stored on
your device and is not sent as a selection to the tile provider.

## Optional cloud text-to-speech

EveryPods includes an optional ElevenLabs text-to-speech engine for notification
and battery announcements. This feature is off unless you select ElevenLabs and
enter an API key that you control.

When enabled, the announcement text, selected voice and language settings, and
your API key are transmitted directly to ElevenLabs over HTTPS so it can
generate speech. EveryPods does not operate a proxy and does not receive a copy
of that information. ElevenLabs processes that data under its own terms and
privacy policy.

If you do not enable ElevenLabs, announcements use Android system
text-to-speech and are not sent to ElevenLabs. You can remove an API key at any
time in the app settings.

## Permissions

EveryPods asks Android for permissions only to provide the features you choose
to use. These can include Bluetooth discovery and connection, notifications,
foreground connected-device operation, optional call controls, notification
announcements, accessibility-based camera controls, overlays, startup after a
restart, and battery-optimization exclusions. Android shows and manages these
permissions.

## Your choices and data deletion

You can change EveryPods settings, revoke permissions, remove an optional API
key, or stop using a feature at any time. You can delete locally stored app data
by clearing EveryPods storage in Android Settings or by uninstalling the app.

## Support and external links

When you choose a support or repository link, EveryPods opens your selected
email or browser app. Information you send through those services is handled by
the relevant provider. Do not post Bluetooth addresses, API keys, or diagnostic
logs containing personal information in public issue trackers.

## Contact

For privacy questions, contact **automated.ventures.apps@gmail.com**.

EveryPods is an independent open-source project and is not affiliated with or
endorsed by Apple. AirPods is a trademark of Apple Inc.
