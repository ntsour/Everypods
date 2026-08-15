# Privacy

EveryPods is designed to keep app settings and connection state on the Android
device. The app does not include an Internet permission in its Android
manifest. Support links open the user's selected email or browser application;
those applications handle anything the user chooses to send.

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

This document describes the current Android build. Review it whenever a new
network service, analytics component, or data export feature is added.
