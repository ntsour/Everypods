# Android UI design contract

Agreed with the user on 18 October 2026. This is the implementation target; the [hierarchy audit](../audits/2026-10-18-ui-hierarchy.md) remains the earlier source snapshot.

## Accepted decisions

1. Connected Home uses six category tiles: **Controls, Audio, Connection, Smart features, App settings, Help**. Disconnected Home keeps its existing status/reconnect, peer, battery, connection, handover, Finder and support layout; normalize its headings and group surfaces to the shared style without replacing it with category tiles.
2. Settings are edited inline in their category. Dedicated screens remain for complex tools: notification announcements/app selection, gym timer/actions, finder, head gestures, equalizer, device naming, permissions, connected devices, support and diagnostics/legal information.
3. Sentence case; section headings outside cards; one 18dp rounded surface per group; no nested settings cards. Keep SF Pro. Primary setting labels are 16sp Normal; supporting text 13sp Normal. Screen title 20sp Medium; section title 13sp Semibold and accessibility heading semantics. Essential instructions must not use 12sp Light.
4. Head gestures shows everyday controls first. Orientation/velocity, tracking packets and gesture tests are behind a collapsed Advanced and diagnostics disclosure.
5. Canonical category content owns state/actions and grouping. Old navigation routes remain compatible and resolve to that content. ANC profiles remains hidden. No incidental feature launch.

## Approved hierarchy

```text
Controls
├─ Stem actions: left/right × single/double/triple/long press
├─ Listening modes included in the long-press cycle
├─ Call controls
├─ Press speed
├─ Press-and-hold duration
└─ Listening controls: single-bud cancellation, tone, volume swipe
Audio
├─ Personalized volume / Equalizer
├─ Conversation awareness: enable, pause music, relative volume, target volume
├─ Hearing protection [where supported]
├─ Adaptive audio strength
└─ Microphone
Connection
├─ Device: name, disconnect
├─ Automatic connection [one group]
│  ├─ Handover: enable, connected devices
│  ├─ Connect to last device when case opens
│  ├─ Connect when receiving calls
│  └─ Connect when starting media
└─ Wear detection: automatic ear detection, disconnect when not wearing / permission reason
Smart features
├─ Gym mode and links to gym tools
├─ Tools: Announcements, Find My case, Head gestures
├─ Automation [one group]
│  ├─ Sleep detection [where supported]
│  ├─ Sleep timer: duration presets, remaining time, cancel
│  ├─ Optimized charge
│  └─ Resume after calls
└─ Battery alerts and threshold
App settings
├─ App language
├─ Widget
├─ Connection popups
├─ Background activity
└─ Permissions
Help
├─ Support
├─ AirPods information
├─ Phone information
├─ App information
├─ Diagnostics [build visibility applies]
└─ Legal / contact
```

## Component ownership

`SettingsSection` owns the external semantic heading and one `SettingsGroup` surface. A group provides `LocalSettingsGroup`; shared toggles, sliders, choice lists and navigation rows render inline under it. Row-specific help stays inside the row; explanations for the whole section may appear as a footer outside its card. Menus/dialogs are modal surfaces rather than nested page cards. Live timer/finder/tracking displays and support heroes retain purpose-specific visualization styles.

Search opens the canonical location without changing settings, granting permission, starting a timer, disconnecting or purchasing. Old route aliases share the canonical category implementation. Search retains English technical aliases while using localized titles/categories.

## Delivery evidence

Implementation follows the accepted hierarchy. The Connection category combines handover and automatic-connection controls in one “Automatic connection” section, with handover controls first and connect-to-last-device immediately after. The disconnected Home retains its previous content and order, with typography and group surfaces normalized. A truly disconnected Home does not render AirPods artwork or battery imagery; an active Bluetooth audio route uses the connected surface even when AACP-only controls are unavailable. The peer-connection warning is a single collapsed row with details and retry revealed on expansion.

Build and device evidence is recorded in the completion report. A compile or install alone does not certify every screen, conditional state, translation or physical-headset behavior.
