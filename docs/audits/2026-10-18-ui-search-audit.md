# EveryPods UI consistency and search audit — 18 October 2026

Status: source audit and proposed implementation plan. No application code, settings, commits, or installed apps changed.

## Scope and evidence limits

Inspected the current working tree, including its existing uncommitted localization/UI changes, all 37 Kotlin screen files, navigation registration, search implementation, shared UI primitives, resource labels, search instrumentation test, and auxiliary UI entry points. The screen inventory below records source-level observations; it is not a screenshot approval of every screen. No build, test suite, device interaction sweep, purchase, permission change, or headset behavior was performed. Two ADB devices were listed; their running UI/build was not established. The checked-in `ios/HandoverLab` directory contains no application source, so there is no production iOS interface here to audit.

## Findings, in priority order

### P1 — Search has confirmed broken and misleading destinations

- **Six results navigate to an empty screen.** `MainActivity.kt:495` registers `app_settings` with an empty body. All six “Connect When…” results use it. The current receiving-call and starting-media toggles actually live in `CategoryScreen.kt:1053` under Audio & Connection. Reconcile the other four historical settings with current functionality before retaining them.
- **Controls Configuration does not open the controls configuration.** The route invokes `VolumeControlScreen`, which only exposes volume swipe speed (`VolumeControlScreen.kt:60–101`). Press speed, hold duration, single-bud cancellation, tone volume and volume enable live in `ControlsConfigurationContent` (`CategoryScreen.kt:410`). Searches for “press speed”, “hold duration” and “tone” currently match a result whose destination omits these controls.
- **Disconnect When Not Wearing opens a screen without that setting.** Its result targets `connection_settings`; `ConnectionSettingsScreen.kt:43` renders `ConnectionSettings` only. The actual toggle uses AppSettingsViewModel inside `CategoryScreen.kt:1045` and is privileged-permission gated.
- **Left/right/gesture results discard their specificity.** All eight press results open `press_actions` without arguments; `PressActionsScreen.kt:49` initially selects left. A right-bud query therefore opens with the wrong bud selected and no target gesture highlight.
- **Peer Android Device opens general connection settings**, although a dedicated `paired_devices` route exists. Point management queries directly to that screen; distinguish choosing the current peer from adding/removing a peer.
- **Direct location is almost absent.** Only Gym Mode has an anchor in the index. The other results do not identify a control or scroll to it. Screen arrival alone does not satisfy direct navigation.

### P1 — Search coverage and discovery are incomplete

- Index labels, categories and keywords are English literals (`AirPodsSettingsScreen.kt:182–239`); localized UI labels are not the search vocabulary. Category metadata and many section headings are also literal English.
- App language is absent despite being the first control in active App Settings (`CategoryScreen.kt:871`).
- Missing dedicated targets include case-open last-device auto-connect, paired-device management, individual control-configuration settings, conversation pause/relative-volume/volume, battery-alert threshold, and many announcement, gym and finder controls (see coverage backlog).
- Search is shown only for locally connected or qualifying A2DP devices (`AirPodsSettingsScreen.kt:427`). App-only settings, permissions and help should be searchable when disconnected too.
- Matching is a substring of one label/category/keyword (`AirPodsSettingsScreen.kt:397–407`), with no token combination, accent folding or relevance ranking. A whitespace-only query becomes an empty substring and returns every entry. Preserve useful aliases while making the localized label primary.
- No availability metadata exists in SearchableItem: premium, AACP, capability, permission and connection gates are not represented. A result can lead to missing/disabled content without explaining why.

### P2 — Heading hierarchy is inconsistent, including card placement

- `MenuSectionHeader` is **12sp semibold uppercase**, with page background (`AirPodsSettingsScreen.kt:256`).
- `ExternalSectionHeader` is **16sp semibold uppercase** (`CategoryScreen.kt:1152`).
- `StyledToggle(title=…)` is **14sp bold, original case** (`StyledToggle.kt:105`).
- Announcement sections introduce further 13sp bold and 14sp variants. Large timer values and visualization labels are legitimate special-purpose typography; they should not be flattened into setting text.
- `CategoryScreen` places Conversation Awareness, Hearing Protection, Bluetooth Control, Adaptive Audio, Automation, Pop-up Animations and takeover headers inside enclosing rounded-card columns. Because MenuSectionHeader paints the page color, this also cuts background-colored strips through a card. Move section labels outside their cards rather than merely restyling the text.
- Category Audio embeds components that draw their own headings and cards (`CategoryScreen.kt:995–1040`), so a nominal outer card contains other independently styled sections.

### P2 — Nested cards and competing shape/spacing systems

- New wrappers commonly use 18dp corners; StyledToggle and StyledSelectList use 28dp, notification/picker/profile surfaces use 20dp, and older screens retain 28dp. These are not all inappropriate shapes, but equivalent setting groups currently differ.
- Phone Battery, Popup Animations, Smart Automation and Conversation Awareness wrap `independent=true` StyledToggle controls inside 18dp cards. Those controls draw their own 28dp cards and spacing. This causes duplicated containment/insets; a wrapper's shape alone cannot fix it.
- MenuDivider uses 0.5dp with 16dp inset and `0x30888888`; older audio/connection components use 1dp, 12dp inset and `0x40888888`.
- Disabled opacity differs (shared DisabledAlpha=0.45, menu rows=0.4 and ad-hoc copies). Standardize both visual treatment and the reason shown for unavailable features.
- Background, accent, text opacity, font and dimensions are copied throughout screens instead of semantic tokens. StyledToggle/StyledSelectList remember some theme-derived background values without a theme key; verify theme switching while visible, including pressed/disabled states.
- StyledToggle uses a fixed 55dp row height. Long localized labels and larger text need minimum height and wrapping. The fixed scaffold title/action layout also needs long-title and font-scale verification.

### P2 — Names and localization undermine a coherent interface

- “Find my Case” mixes capitalization; its destination is titled “Find Nearby”. Choose one visible name and retain the other as an alias.
- Search says “Head Gestures”; the destination uses “Head Tracking”. Clarify whether tracking is a diagnostic subsection and title the user task consistently.
- “Controls Configuration” routes to a page titled “Volume Control”; “Device Name” opens “Name”; “Version Info” opens “Version”. Keep result and destination terminology aligned.
- “App language” / “Speech language” coexist with title-case labels such as “Left Bud” and “Pop-up Animations”. Adopt sentence case in English while retaining proper product names/acronyms and locale-specific conventions.
- Spanish `ui_anc_profiles` currently reads “Perfiles del Congreso Nacional Africano” (`values-es/strings.xml:314`), translating ANC as an organization. Correct audio terminology before exposing this dormant screen. Resource parity cannot establish translation quality.

### P2 — Existing navigation tests can pass over wrong content and miss omissions

`SearchIndexNavigationTest.kt` iterates only existing index entries, so missing features are never tested. It checks root tags, not the target control (except Gym Mode); this misses the Controls Configuration mismatch. It assumes search is available on launch without arranging connection state. PurchaseScreen has no `dest_purchase_screen` tag, and `app_settings` has no content/tag. Query/highlight/state restoration and localized queries are not checked. Do not claim the test passed: it was inspected, not run.

### Reachability / duplication to resolve

`AncProfilesScreen` has no call site or registered route in current main source. Treat it as dormant product functionality: decide whether to expose it with capability/availability handling or explicitly retire it. Do not add a result to an unregistered screen. Legacy AppSettingsScreen source is not the live App Settings interface; remove duplication only after redirecting old routes. `smart_features` is another registered empty compatibility route. `adaptive_strength` and `adaptive_audio` reach the same component and should have one canonical destination plus a working alias.

## Proposed graphical standard

This is the proposed standard for approval before implementation:

| Element | Standard |
|---|---|
| Page title | SF Pro, 20sp medium, centered; home device title remains start-aligned as a deliberate home variant |
| Section heading | Outside the card; 13sp semibold, sentence case, secondary semantic text color; 16dp inset aligned with row text |
| Rows | 16sp regular primary label; 13sp regular supporting text; minimum 56dp height with expansion for wrapping |
| Cards | One 18dp group surface per section; controls inside render as rows without their own card/background |
| Page/card colors | Keep current light page #F2F2F7 and white card; dark page black and card #1C1C1E, expressed as shared tokens |
| Spacing | 16dp page and card horizontal inset; 24dp between sections; 8dp heading-to-card; shared row insets |
| Dividers | One semantic separator token, 0.5dp, 16dp inset; verify contrast in both themes |
| States | Shared selected/pressed/disabled/error styles; availability reason and accessible semantics, not opacity alone |
| Exceptions | Timer digits, live radar/head visualizations, purchase hero, sheets and compact widget/QS layouts retain purpose-specific layout with shared colors/type roles |

Introduce `SettingsPage`, `SettingsSection(title, footer, targetId)`, `SettingsCard`, `SettingsRow`, `SettingToggleRow`, `SettingChoiceRow`, `SettingSliderRow`, and semantic theme/type/spacing tokens. Containers own background, grouping and section title; child rows own behavior. Extract shared section content so category browsing and direct destinations cannot drift again.

## Search model and direct navigation design

Create a central feature catalog, shared by browsing and search. Each user-facing function has a stable feature ID, localized title/description/category/breadcrumb, localized keyword resource plus durable aliases, canonical destination, optional target/parameters, and availability rule. Adding a locale must remain a resource-only task.

Canonical settings should be routed by stable target ID, not by English label. For example `controls.stem.right.double` selects the right bud and reveals/highlights double press; `audio.takeover.ringing` opens Audio & Connection at the receiving-call toggle; `app.language` locates the app-language picker. Reuse target-aware scrolling/highlighting on all long destinations, not only category pages. Pass exact target state to the destination and wait until layout is ready; unknown/retired targets resolve to a useful parent with a message.

Keep search accessible offline/disconnected. Show applicable results with a clear unavailable/permission/premium reason and an explanation destination; do not change a setting, purchase, grant permission, start timers, send email, or disconnect merely because a result was selected. Conditional controls should reveal an explanatory location without silently enabling their parent. Device-only features need correct capability checks, not a fallback model guess. Normalize trim, whitespace, Unicode case and accents; match tokens across title/aliases/breadcrumb, and rank exact title > title prefix/tokens > aliases > category/description. Localized names must work; English technical aliases may remain helpful. Restore query, results position and prior browsing position on Back.

## Coverage backlog: functional targets to register

- **Home/device:** device rename, battery/status details and listening-mode controls where interactive; connect/disconnect actions should open their control surface. Every read-only status need not become a separate search hit.
- **Controls:** each bud × gesture, listening-mode inclusion, answer/end/mute call mappings, press speed, hold duration, single-AirPod ANC, tone volume, volume enable and swipe speed; gym mappings for each supported gesture/bud.
- **AirPods settings:** conversation awareness enable, pause music, relative volume and volume level; hearing-protection options; Bluetooth control and device name.
- **Smart:** notification announcements and every user-configurable subsection; finder actions/settings; head gesture actions/settings; adaptive strength; sleep detection; optimized charging; resume after calls; battery alerts and threshold; sleep timer duration; gym mode and gym timer mode/duration/intervals/laps/announcement and speech settings.
- **Announcements:** notification access, enabled state, skip calls/media, in-ear condition, voice engine, system/provider voice, API-key configuration, connection test/voice preview, notification content, speech language, quiet-hours enable/mode/start/end, and per-app inclusion. Never index secret values or dynamic notification content.
- **App:** app language/system selection, phone battery widget, both popup styles, background activity guidance and individual permission controls.
- **Audio/connection:** personalized volume, conversation awareness entry, adaptive audio, equalizer and its editable controls/presets where supported; handover enable, selected peer, add/remove paired device, case-open last-device auto-connect, ear detection, disconnect when not wearing, receiving-call and starting-media takeover. Remove retired “Connect When…” functions after checking actual state owners.
- **Help:** version, troubleshooting (respect its current debug/product visibility rule), support purchase, licenses, email and issue-report screens. Search opens forms/screens; external sending requires user action.
- **Auxiliary surfaces:** quick settings controls, lock-screen gym timer and widget actions map to the corresponding in-app configuration or meaningful system instructions. These compact surfaces should not duplicate the entire settings hierarchy.
- **Dormant:** ANC profiles requires an explicit expose/retire decision and proper route before catalog inclusion.

## Implementation sequence and completion gates

1. **P1 navigation correctness.** Redirect empty compatibility routes; fix wrong configuration/disconnect/peer destinations; add target arguments for all stem results. Remove obsolete results only after verifying functionality owners. Gate: each existing result reaches its exact control or useful availability explanation.
2. **Shared design primitives.** Implement tokens and row/group components; convert the six categories first, then direct pages using the same section composables. Move headings outside cards and remove nested surfaces. Gate: matching category/direct sections use the same composable and state source.
3. **Catalog and search completeness.** Populate stable targets from the inventory, localized vocabulary and conditional-state rules; make search available disconnected; add token matching/ranking, anchors and Back restoration. Gate: every interactive function has a catalog target or explicit documented exclusion.
4. **Remaining surfaces and copy.** Migrate announcement, gym, finder, tracking, purchase/help, sheets/dialogs and compact surfaces; reconcile names/case and translation defects. Resolve dormant ANC profiles separately from existing-function repair.
5. **Verification.** Unit tests for catalog uniqueness, registered destinations/aliases, availability, localization and matching. Instrumentation iterates the complete function catalog (not just current search entries), asserts the exact control, bud/gesture state and Back behavior. Test unavailable results with explicit state fixtures. Screenshot review of each reachable page/sheet in light/dark, English/Spanish plus a long-label locale, 1.0/1.3/2.0 font scale, small/large display and relevant keyboard/system-inset states. Device sweep covers disconnected, A2DP-only, AACP, premium/nonpremium and denied permissions; physical-headset checks validate only behavior that depends on hardware.

No build/install is a substitute for the final screenshot and interaction sweep. Keep changes in narrow reviewable batches and preserve the substantial existing unrelated working-tree changes.

## Screen source inventory

Equivalent card groups should follow the standard above. Values below are detected declarations, not a claim that every listed radius or text size is wrong. Shared components can add further styling.

| Screen source | Declared title / entry | Card radii declared (dp) | Migration focus |
|---|---|---|---|
| [AccessibilitySettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AccessibilitySettingsScreen.kt) | Accessibility | 0, 28 | Share controls configuration with category; gates and heading hierarchy |
| [AdaptiveStrengthScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AdaptiveStrengthScreen.kt) | Customize Adaptive Audio | shared | Canonical adaptive audio title/route and slider target |
| [AirPodsSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AirPodsSettingsScreen.kt) | if (searchActive) "" else deviceName.text | 0, 10, 14, 16, 18, 20, 22, 24, 27 | Search/catalog; home variant; shared menu primitives |
| [AncProfilesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AncProfilesScreen.kt) | ANC profiles) { topPadding, _, bottomPadding -> | 20 | Unreachable; expose/retire decision; profile dialogs and translation |
| [AnnouncementAppPickerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AnnouncementAppPickerScreen.kt) | Apps — disable to silence) { topPadding, _, bottomPadding -> | 20 | Direct search entry; list typography, filtering and empty states |
| [AppPermissionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AppPermissionsScreen.kt) | Permissions) { topPadding, _, bottomPadding -> | 8, 12, 14, 18 | Permission targets/explanations; system cards and status colors |
| [AppSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AppSettingsScreen.kt) | Settings | 28 | Legacy source; active route is empty; redirect and deduplicate |
| [AudioSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AudioSettingsScreen.kt) | Audio Settings) { topPadding, hazeState, bottomPadding -> | shared | Use canonical shared audio sections; target-aware entry |
| [BluetoothControlScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/BluetoothControlScreen.kt) | Bluetooth Control) { topPadding, hazeState, bottomPadding -> | 18 | Shared typography/surface/spacing; localized naming; function targets |
| [CallControlsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/CallControlsScreen.kt) | Call Controls) { topPadding, hazeState, bottomPadding -> | shared | Shared typography/surface/spacing; localized naming; function targets |
| [CategoryScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/CategoryScreen.kt) | title) { topPadding, hazeState, bottomPadding -> | 12, 14, 18 | All six categories; external headings; flatten embedded cards; canonical shared sections |
| [ConnectionSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ConnectionSettingsScreen.kt) | Connection Settings) { topPadding, hazeState, bottomPadding -> | shared | Missing disconnect/takeover controls; peer direct navigation; gating parity |
| [ConversationAwarenessScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ConversationAwarenessScreen.kt) | Conversation Awareness) { topPadding, hazeState, bottomPadding -> | 18 | Flatten groups; target pause/relative-volume/level |
| [DebugScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/DebugScreen.kt) | Debug | 4, 8, 12 | Respect visibility policy; standard controls and useful troubleshooting name |
| [EmailSupportScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/EmailSupportScreen.kt) | Email Support) { topPadding, hazeState, bottomPadding -> | 18 | Shared typography/surface/spacing; localized naming; function targets |
| [EqualizerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/EqualizerScreen.kt) | Equalizer) { topPadding, _, bottomPadding -> | 28 | Search coverage; presets/sliders; token migration and state fixtures |
| [GitHubIssuesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GitHubIssuesScreen.kt) | GitHub Issues) { topPadding, hazeState, bottomPadding -> | 18 | Shared typography/surface/spacing; localized naming; function targets |
| [GymPressActionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GymPressActionsScreen.kt) | Gym Press Actions) { topPadding, hazeState, bottomPadding -> | 12, 18 | Bud/gesture targets; compact text and dropdown consistency |
| [GymTimerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GymTimerScreen.kt) | Gym Timer) { topPadding, hazeState, bottomPadding -> | 8, 10, 12, 18 | External setting headings; keep timer typography; mode-specific targets |
| [HeadTrackingScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/HeadTrackingScreen.kt) | Head Tracking | 28 | Head gestures naming; distinguish diagnostic visualization; action targets |
| [HearingProtectionScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/HearingProtectionScreen.kt) | Hearing Protection | shared | Shared typography/surface/spacing; localized naming; function targets |
| [ListeningModeConfigScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ListeningModeConfigScreen.kt) | Listening Mode Configuration) { topPadding, hazeState, bottomPadding -> | 18 | Shared typography/surface/spacing; localized naming; function targets |
| [MicrophoneSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/MicrophoneSettingsScreen.kt) | Microphone Settings) { topPadding, hazeState, bottomPadding -> | shared | Shared typography/surface/spacing; localized naming; function targets |
| [NotificationAnnouncementsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/NotificationAnnouncementsScreen.kt) | Notification Announcements) { topPadding, _, bottomPadding -> | 10, 20 | Section hierarchy, provider fields, voices, quiet hours; complete targets |
| [OpenSourceLicensesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/OpenSourceLicensesScreen.kt) | Open Source Licenses | shared | Shared typography/surface/spacing; localized naming; function targets |
| [PairedDevicesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PairedDevicesScreen.kt) | Connected Devices) { topPadding, hazeState, bottomPadding -> | 18 | Searchable management/add/remove; dialog typography |
| [PhoneBatteryScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PhoneBatteryScreen.kt) | Battery in Widget) { topPadding, hazeState, bottomPadding -> | 18 | Remove independent card nested in card |
| [PopupAnimationsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PopupAnimationsScreen.kt) | Pop-up Animations) { topPadding, hazeState, bottomPadding -> | 18 | Remove independent cards nested in card; separate targets |
| [PressActionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PressActionsScreen.kt) | Press Actions) { topPadding, hazeState, bottomPadding -> | 18 | Bud/gesture target arguments; shared controls with category |
| [PressAndHoldSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PressAndHoldSettingsScreen.kt) | name | shared | Legacy target arguments; unify with current stem controls |
| [ProximityFinderScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ProximityFinderScreen.kt) | Find Nearby | 8, 12, 14, 18, 28 | Find my case naming; radar exceptions; finder control targets |
| [PurchaseScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PurchaseScreen.kt) | Support EveryPods | 28 | Retain hero; group tokens; missing destination tag |
| [RenameScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/RenameScreen.kt) | Name | shared | Shared typography/surface/spacing; localized naming; function targets |
| [SleepTimerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/SleepTimerScreen.kt) | Sleep Timer) { topPadding, hazeState, bottomPadding -> | 18 | Shared typography/surface/spacing; localized naming; function targets |
| [SmartAutomationScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/SmartAutomationScreen.kt) | Automation) { topPadding, hazeState, bottomPadding -> | 18 | Flatten toggle groups; target each automation/threshold |
| [VersionInfoScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/VersionInfoScreen.kt) | Version | 28 | Shared typography/surface/spacing; localized naming; function targets |
| [VolumeControlScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/VolumeControlScreen.kt) | Volume Control) { topPadding, hazeState, bottomPadding -> | 18 | Incorrect Controls Configuration destination; complete shared section |

## Existing search index: exhaustive destination review

“Page only” means a route is wired in source, but exact control arrival and live behavior are unverified. All rows also inherit English-only vocabulary and missing availability metadata. This is not a passing navigation test.

| Existing search label | Current destination | Source audit result / required change |
|---|---|---|
| Left Bud — Single Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Left Bud — Double Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Left Bud — Triple Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Left Bud — Long Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Right Bud — Single Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Right Bud — Double Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Right Bud — Triple Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Right Bud — Long Press | `press_actions` | Bud/gesture lost; always initially left; add selected bud and gesture target |
| Listening Mode Configuration | `listening_mode_config` | Page only; add exact function target and availability handling |
| Call Controls | `call_controls` | Page only; add exact function target and availability handling |
| Volume Control | `controls_configuration` | Page only; add exact function target and availability handling |
| Controls Configuration | `controls_configuration` | WRONG CONTENT: only volume swipe speed; use full controls section |
| Gym Mode | `category/smart` | Only entry with anchor; live scroll/highlight still needs verification |
| Gym Press Actions | `gym_press_actions` | Page only; add exact function target and availability handling |
| Device Name | `rename` | Page only; add exact function target and availability handling |
| Hearing Protection | `hearing_protection` | Page only; add exact function target and availability handling |
| Conversation Awareness | `conversation_awareness` | Page only; add exact function target and availability handling |
| Disconnect | `bluetooth_control` | Page only; add exact function target and availability handling |
| Notification Announcements | `notification_announcements` | Page only; add exact function target and availability handling |
| Find my Case | `proximity_finder` | Page only; add exact function target and availability handling |
| Head Gestures | `head_tracking` | Page only; add exact function target and availability handling |
| Adaptive Audio | `adaptive_audio` | Page only; add exact function target and availability handling |
| Sleep Detection | `smart_automation` | Page only; add exact function target and availability handling |
| Optimized Charging | `smart_automation` | Page only; add exact function target and availability handling |
| Resume Media After Call | `smart_automation` | Page only; add exact function target and availability handling |
| Battery Alerts | `smart_automation` | Page only; add exact function target and availability handling |
| Sleep Timer | `sleep_timer` | Page only; add exact function target and availability handling |
| Gym Timer | `gym_timer` | Page only; add exact function target and availability handling |
| Battery in Widget | `phone_battery` | Page only; add exact function target and availability handling |
| Pop-up Animations | `popup_animations` | Page only; add exact function target and availability handling |
| Bottom Sheet Popup | `popup_animations` | Page only; add exact function target and availability handling |
| Dynamic Island Popup | `popup_animations` | Page only; add exact function target and availability handling |
| Permissions | `permissions` | Page only; add exact function target and availability handling |
| Background Activity | `category/appsettings` | Category opens without anchor; add target |
| Connect When Disconnected | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Connect When Idle | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Connect When Playing Media | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Connect When On Call | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Connect When Receiving a Call | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Connect When Starting Media | `app_settings` | BROKEN: empty route; reconcile obsolete item and route to current owner |
| Audio Settings | `audio_settings` | Page only; add exact function target and availability handling |
| Personalized Volume | `audio_settings` | Page only; add exact function target and availability handling |
| Handover to Other Devices | `connection_settings` | Page only; add exact function target and availability handling |
| Peer Android Device | `connection_settings` | Use paired_devices for management or explicit peer-selector target |
| Ear Detection | `connection_settings` | Page only; add exact function target and availability handling |
| Disconnect When Not Wearing | `connection_settings` | MISSING CONTROL: use category/audio target and permission explanation |
| Microphone Settings | `microphone_settings` | Page only; add exact function target and availability handling |
| Connection Settings | `connection_settings` | Page only; add exact function target and availability handling |
| Open Source Licenses | `open_source_licenses` | Page only; add exact function target and availability handling |
| Version Info | `version_info` | Page only; add exact function target and availability handling |
| Support EveryPods | `purchase_screen` | Destination exists; missing destination test tag |
| Email Support | `email_support` | Page only; add exact function target and availability handling |
| GitHub Issues | `github_issues` | Page only; add exact function target and availability handling |

Inventory totals: 37 screen source files; 53 existing search entries; 6 results to the confirmed empty legacy route.

## Live verification follow-up

See [live findings and screenshots](2026-10-18-live/README.md). The installed 1.0.4-rc19 build differs from this checkout; notably it has localized search labels and a heart-rate entry. Source-level English-only index findings are not a description of that installed build.
