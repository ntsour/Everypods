# Privacy

EveryPods is designed to keep app settings and connection state on the Android
device. The app includes Internet access solely for the optional ElevenLabs
cloud text-to-speech feature described below. Support links open the user's
selected email or browser application; those applications handle anything the
user chooses to send.

The app requests permissions for Bluetooth connection and scanning, foreground
device services, notifications, phone-call controls, notification listening,
accessibility camera-control support, overlays, and battery optimization where
the corresponding feature is enabled. Android displays and controls these
permissions. Features that are not enabled do not need to be used.

Bluetooth identifiers, feature preferences, local diagnostic information, and
device state are stored locally so the app can operate. Do not include private
Bluetooth addresses or logs in public GitHub Issues.

Google Play Billing is handled by Google Play. EveryPods does not receive or
store payment card information.

## Optional cloud text-to-speech

EveryPods includes an optional ElevenLabs text-to-speech integration for
notification and battery announcements. A user must select that engine and
enter their own ElevenLabs API key before the integration can be used. When
enabled, the selected announcement text, the chosen voice and language
settings, and the user's API key are sent directly to ElevenLabs to generate
speech. EveryPods does not operate a proxy or receive a copy of that data.
ElevenLabs' own privacy terms apply to that processing.

The API key and engine preference remain stored only in the app's local Android
storage. Do not enter a key you do not control, and remove it from the app
settings before sharing a device. If the feature is not enabled, announcements
use Android system text-to-speech and are not sent to ElevenLabs.

This document describes the current Android build. Review it whenever a new
network service, analytics component, or data export feature is added.
