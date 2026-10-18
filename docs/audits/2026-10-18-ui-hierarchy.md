# EveryPods Android interface hierarchy — working review document

**Design decisions accepted:** see the [implementation contract](../design/android-ui-contract.md). The trees below remain the pre-redesign snapshot for comparison.

Snapshot: 18 October 2026. **Current implementation**, followed by **proposed rules for discussion**. This document does not approve the existing groupings.

This is a source-based inventory of the current uncommitted working tree: all 37 Kotlin screen files, six category variants, registered routes, shared controls, and auxiliary surfaces. It incorporates the live audit's observed hierarchy problems, but is not a new screenshot certification of every state. Conditional branches are included even when the connected AirPods model does not expose them. English resource labels identify functions; the running app may translate them. Runtime lists (apps, peers, voices, signals) are represented by a repeated item rather than enumerating personal data.

The latest notification-scroll change was still being built when this documentation request arrived. Its installation/visual validation is not claimed here. No further UI restructuring is being applied as part of this document.

## 1. Vocabulary we can use together

| Term | Plain meaning | Android / Jetpack Compose equivalent |
|---|---|---|
| Screen / page | A complete destination, usually with a title and Back button | Navigation destination; `composable` route |
| Top app bar | The title and navigation/actions at the top | Usually `TopAppBar`; this app uses custom `StyledScaffold` plus navigation controls |
| Screen title | The name of the page | A `Text` with a title typography role; not automatically an HTML-style H1 |
| Section | A meaningful group such as “Call controls” | A product/layout concept; normally a heading plus a container of related rows |
| Section heading | Text naming a section | `Text`; should also expose heading semantics for accessibility |
| Subsection heading | A named division within a section | Another explicit typography/semantic role; avoid introducing it just to change text size |
| Surface | An area with its own background/shape | `Surface`, `Card`, or a layout with `Modifier.background` |
| Card / group container | A visible rounded surface containing related content | This app often uses `Column.background(RoundedCornerShape(...))`, not Material `Card` |
| Frame | An ambiguous general word for a boundary | Prefer **surface**, **card**, **outlined group**, or **layout container**, depending on what is visible |
| Layout container | An invisible structure arranging children | `Column`, `Row`, `Box`; these are not cards unless given a surface |
| Settings row | One preference, action, or navigation entry | A custom row or Material `ListItem` pattern |
| Primary text / label | The setting's name | `Text`, often a body typography role |
| Supporting text | An explanation immediately associated with a row | Secondary text; it may be inside the row or a footer outside the card |
| Value / trailing content | Current value, switch, chevron, or action on the right | `Switch`, trailing `Text`, `Icon`, button, etc. |
| Section footer / helper text | Explanation for a whole group | Supporting text below its card, aligned with row text |
| Single-choice group | Pick exactly one option | Radio-button list or segmented control; app uses `StyledSelectList` |
| Multi-choice group | Several options may be enabled | Checkbox/toggle list; selected listening modes behave this way |
| Segmented control | Adjacent mutually exclusive choices | Material segmented buttons, or the app's custom equivalent |
| Dialog | A focused overlay requiring a choice | `AlertDialog`, custom dialog, Android time picker |
| Modal bottom sheet | A panel rising from the bottom over a screen | `ModalBottomSheet` / app's `StyledBottomSheet` |
| Typography role / design token | A named reusable style instead of an arbitrary size | `MaterialTheme.typography` or custom semantic type/color/spacing tokens |
| `sp` / `dp` | Text size / layout size | `sp` respects font scaling; `dp` respects display density. Neither is a screenshot pixel count |
| Sentence case | “Background activity”, “Call controls” | Capitalize the first word, proper names and acronyms; follow each locale's conventions |
| Heading semantics | Tell TalkBack that text is a heading | `Modifier.semantics { heading() }`; making text bold alone does not do this |

**Important:** hierarchy is meaning and containment, not font size alone. A setting label is body text even when it appears above a switch. A card title is not automatically a new navigation level.

## 2. How to read the trees

- Indentation shows **current visual/layout containment**, not merely conceptual relationship.
- `P` = page/top app bar, outside content cards. `H` = section heading. `L` = row/field label. `B` = supporting body/help text. `A` = action. `V` = special live value/visualization.
- `[C18]`, `[C20]`, `[C28]` = rounded surface with radius 18/20/28dp. An indented card inside another card is intentionally shown: it exposes existing nested containment.
- `[layout]` = transparent grouping; `[page]` = directly on the page background. `→` opens another destination or system surface.
- `[conditional]` = shown only for an applicable capability, permission, engine, selected mode, or runtime state. Availability also affects enabled/disabled state.
- `T` = independent `StyledToggle`: optional H above its own C28; L16 inside; B12 Light/60% below that local card. If T sits inside C18, its footer is outside C28 but still inside C18.
- `t` = inline toggle: L16 and optional B12 inside its parent row, without a new resting card surface.
- `S` = `StyledSelectList`: C28 choice surface; L16 option text, optional B14 description. A checkmark is trailing state, not a heading.
- `R` = `MenuNavRow`: L16 + optional B13 + chevron inside its parent. `N` = `NavigationButton`: L16; optional H14 Bold above and B12 Light below; independent form owns C28.
- `SL` = `StyledSlider`: L16 value/end labels; optional H14 Bold and B12 Light helper; independent form owns C28. Custom slider/graph labels can differ.

Every tree's root uses P unless explicitly marked otherwise. See the style register for exact current role variants. Nodes without a printed heading must not be read as implying an existing heading.

## 3. Current style register

Most custom text uses bundled **SF Pro** (`R.font.sf_pro`). This is an Apple-inspired custom Compose interface, not a consistently applied Material 3 typography system. Material text fields, dropdowns, dialogs and parts of the equalizer inherit Material typography unless explicitly overridden.

| ID / role today | Size, weight, color | Container / placement today |
|---|---|---|
| P — `StyledScaffold` title | 20sp Medium; primary foreground; one line with ellipsis | Outside content cards; generally centered. Home title is start-aligned. Custom translucent/blurred header area |
| H-menu — `MenuSectionHeader` | 13sp Semibold; primary at 50%; literal/resource capitalization retained | Wherever caller inserts it: both page and inside enclosing cards. No uppercase transformation now |
| H-external — `ExternalSectionHeader` | 13sp Semibold; primary at 50% | Page-level heading in Controls; same current type size as H-menu, different layout helper |
| H-control — toggle/slider/navigation title | 14sp Bold; primary at 60% | Above local control card, but may remain inside an outer card |
| H-announcement | 14sp Bold/60% for major labels; 13sp Bold/60% for system-voice/voice labels | Permissions heading inside C20; other section labels generally on page; voice sublabels in engine-specific content |
| H-timer | 16sp Semibold | Inside C18 mode/duration/HIIT/laps groups |
| L — common setting label | 16sp Normal, primary | Inside row/card; `MenuNavRow`, `StyledToggle`, standard choice/slider label |
| B-menu — `captionStyle` | 13sp Normal, primary at 50% | Row subtitle or help text; caller decides inside/outside |
| B-toggle/navigation/slider | 12sp, often Light, primary at 60% | Outside independent control's local card; inside inline row when applicable |
| B-choice | 14sp, secondary | Under option label inside C28 |
| Search result | 15sp primary label; 12sp category at 45–50% | Inside search results card, category visually below result name |
| Stem-action grid | 10sp gesture labels; 14sp action values; dropdown items 15sp; bud label 12sp Medium | Cells inside C18, each with its own small outlined/rounded affordance |
| Permission row | 15sp Medium label; 12sp body; 13sp Medium action | Permission group C18; warning/information surfaces use other small radii |
| Action text | Typically 13–16sp Medium; accent or contrasting text | Buttons; varying per screen. Some old labels remain uppercase |
| Special values / hero | Timer 56sp; finder up to 24sp; support hero up to 22sp; tracking labels up to 20sp | Purpose-specific live displays/hero cards; not general section-heading levels |
| Diagnostic/technical body | Commonly 12–16sp; some platform/default styles | Debug cards, protocol values and license text; see source appendix |

Current colors: scaffold page light `#F2F2F7`, dark black; common card light white, dark `#1C1C1E`; blue accents vary (`#0088FF`, `#0091FF`, `#0A84FF`). Secondary text often uses opacity rather than a shared semantic color. Shapes vary: category C18, announcement C20, old shared controls C28, whole scaffold clip 52dp. **The 52dp outer clip is an app-level visual frame, not a settings group.**

Current English capitalization is mixed: “Pop-up Animations”, “Speech language”, “Head gestures”, “Volume Control”, and “Quiet hours”. Typeface consistency alone does not make those labels consistent.

## 4. Current screen trees

### 01 — Home, connected (`settings`)

```text
P Device name                     [page/top bar; support and Search actions]
├─ B Connection / audio-route status [page]
├─ V Bud/case artwork and battery levels [page; BatteryView]
├─ H Listening mode               [page; shared NoiseControlSettings]
│  └─ V Mode selector + mode labels [custom segmented surface]
├─ A Support banner               [conditional]
└─ Category tile grid             [separate rounded tiles]
   ├─ AirPods Controls → 03
   ├─ AirPods Settings → 04
   ├─ Smart Features → 05
   ├─ App Settings → 06
   ├─ Audio & Connection → 07
   └─ Help & Support → 08
```

### 02 — Home, disconnected / limited connection (`settings` variants)

```text
P Device/app title + Search       [page/top bar]
├─ Status / reconnect hero        [rounded surface; title/status/body/action]
├─ T Auto-connect on case open (last device)
├─ H / group Handover             [home-specific surfaces]
│  ├─ T Handover to other devices
│  ├─ Repeated peer status / management
│  ├─ T Starting media playback
│  └─ T Receiving a call
├─ A Find My Case → 24             [entry available without full device controls]
├─ App settings / help entries    [home-specific navigation]
└─ Get help group
   ├─ Support EveryPods           [tip card]
   ├─ Email                       [action]
   └─ GitHub                      [action]
```

Limited A2DP models can use the connected presentation with capability-dependent controls. Service-not-ready is a separate centered placeholder: “Starting…” or service error + Retry; it has no normal settings hierarchy. Home variants are not currently one shared grouping system.

**Validation finding (2026-10-18):** the merged state could show the disconnected hero/reconnect card while Bluetooth audio was active, and the peer warning expanded into a multi-line block by default. That mixed the two connection presentations and obscured the hierarchy at larger font scale. Route to the connected presentation whenever local AACP or A2DP is active; reserve the disconnected presentation for no active route, and keep its AirPods artwork/battery view absent. Collapse the peer warning to a one-line disclosure with details and retry inside the expanded state.

### Search — a Home state, not a separate route

```text
P Search text field + Close       [replaces home title; 16sp input]
├─ Results surface
│  └─ Repeated result: L15 title + B12 category + chevron → target
└─ B No-results message           [page, when query has no matches]
```

### 03 — AirPods Controls (`category/controls`)

```text
P AirPods Controls
├─ H-external Stem Actions        [page]
│  └─ C18
│     ├─ Left bud / Right bud selection
│     └─ Two-column gesture grid
│        ├─ Single press × left/right → action dropdown
│        ├─ Double press × left/right → action dropdown
│        ├─ Triple press × left/right → action dropdown
│        └─ Long press × left/right → action dropdown
├─ H-menu Listening Mode Configuration [page]
│  ├─ B Long-press cycling explanation [page]
│  └─ S Off / Transparency / Adaptive / Noise cancellation [multi-selection]
├─ H Call Controls                [page; shared component]
│  └─ C18 Answer/end/mute call mapping rows
└─ H-external Controls Configuration [page]
   └─ C18
      ├─ Press speed: explanation + S Default / Slower / Slowest
      ├─ Press-and-hold duration: explanation + S Default / Shorter / Shortest
      ├─ T Noise cancellation with single AirPod
      ├─ SL Tone volume
      ├─ T Volume control
      └─ Volume swipe speed: explanation + S options
```

Nested C28 choice/toggle/slider surfaces remain inside the last C18. Capability/connection gating changes controls or shows the unsupported-model message.

### 04 — AirPods Settings (`category/settings`)

```text
P AirPods Settings
└─ Transparent content column     [no single enclosing C18 here]
   ├─ R Device name + current name → 09
   ├─ H-menu Conversation Awareness
   │  ├─ T Conversational Awareness
   │  ├─ T Pause music
   │  ├─ T Relative volume
   │  └─ SL Target media volume during conversation
   ├─ H-menu Hearing Protection   [PPE capability conditional]
   │  └─ T EN 352 Protection
   └─ H-menu Bluetooth Control
      └─ A Disconnect
```

Here section headers are effectively outside the individual controls' cards. This differs from Smart/App categories below. Subcontrols are gated by the master/connection state.

### 05 — Smart Features (`category/smart`)

```text
P Smart Features
├─ C18 Gym group                  [no external heading]
│  ├─ T Gym mode                  [nested C28]
│  ├─ R Gym timer + state → 22     [when gym mode enabled]
│  └─ R Configure gym press actions → 21 [when enabled]
├─ C18 Navigation group           [no heading]
│  ├─ R Notification announcements → 25
│  └─ R Find My Case → 24
└─ C18 Mixed feature group
   ├─ R Head gestures + status → 23 [capability conditional]
   ├─ H-menu Adaptive Audio       [inside C18]
   │  └─ SL Customize adaptive audio [nested C28]
   ├─ H-menu Automation           [inside C18]
   │  ├─ T Pause media when falling asleep [capability conditional]
   │  ├─ T Optimized charge limit
   │  ├─ T Resume media after call
   │  └─ T Speak when battery is low
   │     └─ S Threshold 10 / 20 / 30 / 40% [conditional; nested surface]
   └─ H-menu Sleep Timer          [inside same C18]
      ├─ A 15 / 30 / 45 / 60 / 90 min presets
      └─ B Remaining time + Cancel, or idle status
```

### 06 — App Settings (`category/appsettings`; `app_settings` redirects here)

```text
P App Settings
├─ App language                   [own language-setting layout]
│  └─ L Current selection → dropdown: system default / resource-derived languages
└─ C18 Mixed preferences group
   ├─ T Show phone battery in widget [nested C28]
   ├─ H-menu Pop-up Animations    [inside C18]
   │  ├─ T Bottom sheet popup
   │  └─ T Dynamic Island popup
   ├─ H-menu Background Activity  [inside C18]
   │  ├─ B14 Medium green/orange current status
   │  ├─ B13 Explanation
   │  └─ A Allow unrestricted background activity → Android dialog [conditional]
   └─ R Permissions → 28
```

### 07 — Audio & Connection (`category/audio`)

```text
P Audio & Connection
└─ Transparent outer column
   ├─ H Audio                    [shared AudioSettings; page-level]
   │  └─ C18
   │     ├─ t Personalized volume [capability conditional]
   │     ├─ t Conversation awareness [capability conditional]
   │     ├─ N Adaptive audio → 17 [capability conditional]
   │     └─ N Equalizer → 18      [capability conditional]
   ├─ ConnectionSettings component [own C28 surfaces]
   │  ├─ B Setup instructions
   │  ├─ t Handover to other devices
   │  ├─ Peer/current-device status, chooser/management → 27
   │  ├─ t Auto-connect on case open (last device)
   │  └─ t Automatic ear detection [capability conditional]
   ├─ MicrophoneSettings component [C28]
   │  └─ V/L Automatic / Always left / Always right selector
   ├─ T Disconnect when not wearing [privileged permission]
   │  └─ B Permission explanation instead when unavailable
   └─ H-menu Connect to AirPods when your phone is:
      ├─ T Receiving a call
      └─ T Starting media playback
```

### 08 — Help & Support (`category/help`)

```text
P Help & Support
└─ C18 Combined help/about group
   ├─ Support EveryPods row: L16 Semibold + B13 → 29
   ├─ AboutCard                   [owns additional presentation]
   │  ├─ H14 Bold About           [page-colored strip inside outer C18]
   │  └─ C28 Model names / serial numbers / firmware-version entry
   ├─ DeviceInfoCard
   │  ├─ H14 Bold Device info     [page-colored strip inside outer C18]
   │  └─ C28 Manufacturer / phone model / build ID / Android version [L16]
   ├─ AppInfoCard
   │  ├─ H14 Bold About           [another page-colored strip inside outer C18]
   │  └─ C28 App version / version code / build type [L16]
   ├─ R Device protocol versions → 33
   ├─ R Troubleshooting → 34      [non-Play builds]
   ├─ R Email support → external email composer
   ├─ R GitHub issues → external browser
   └─ R Open source licenses → 32
```

Several information components keep their own headings/surfaces inside the parent. App version and device protocol versions are distinct functions.

### 09 — Device Name (`rename`)

```text
P Device Name
└─ StyledInputField               [C28 input; L16; placeholder 16 Light]
   ├─ Editable device name
   └─ Clear / keyboard submission behavior
```

### 10 — Press Actions (`press_actions?bud=…&gesture=…`)

```text
P Press Actions
├─ C18 Stem actions grid          [same two-column interaction pattern as 03]
│  ├─ Left / Right bud selection
│  └─ Single / Double / Triple / Long press × each bud → action dropdown
└─ B Assistant compatibility explanation [page; or unsupported-model message]
```

Bud/gesture arguments select/reveal the requested control. This is another presentation of the category's stem settings.

### 11 — Long Press (`long_press/{bud}`; source `PressAndHoldSettingsScreen`)

```text
P Bud-specific title
├─ S Action: Listening mode / Digital assistant
├─ B Action description           [page]
└─ Listening-mode selection       [when applicable]
   ├─ S Off / Transparency / Adaptive / Cancel noise
   └─ B Explanation              [page; 12–14sp variants]
```

### 12 — Call Controls (`call_controls`)

```text
P Call Controls
├─ H Call Controls                [shared component, 13sp Semibold]
└─ C18
   ├─ L16 Answer call / current gesture
   ├─ L16 End call / current gesture
   └─ L16 Mute/unmute / current gesture
```

Shared component also appears on 03. The repeated page/section title is a review candidate. Gesture mapping is a custom control, not three unrelated navigation links.

### 13 — Listening Mode Configuration (`listening_mode_config`)

```text
P Listening Mode Configuration
└─ C18
   ├─ B Cycling explanation       [inside C18]
   └─ S Off / Transparency / Adaptive / Noise cancellation [nested C28]
```

Same function as 03, but the explanation and list acquire an extra outer card here.

### 14 — Controls Configuration (route) / Accessibility (legacy page)

```text
controls_configuration → 03, scrolled to Controls Configuration [no distinct screen]

P Accessibility                   [accessibility route; legacy separate source]
├─ Press speed / explanation / choice group
├─ Press-and-hold duration / explanation / choice group
├─ H-control Listening mode
│  └─ T Noise cancellation with single AirPod
├─ SL Tone volume
├─ T Volume control
└─ Volume swipe speed / explanation / choice group
```

The accessibility source retains independent C28 styling and local 12/16sp text. It overlaps the category configuration rather than supplying a shared hierarchy. Its remaining detail layout should be retired/shared only after route compatibility is decided.

### 15 — Conversation Awareness (`conversation_awareness`)

```text
P Conversation Awareness
└─ C18
   ├─ t Conversational Awareness
   ├─ t Pause music
   ├─ t Relative volume
   └─ Inline slider Target media volume during conversation [L16 + supporting text; no independent card]
```

Dedicated screen uses inline toggle rows, while 04 uses independent toggle cards. The dedicated screen also sets the slider to inline mode. This is a direct example of the same functionality receiving different containment.

### 16 — Hearing Protection / Bluetooth Control

```text
P Hearing Protection              [hearing_protection]
├─ H-control Workspace Use        [above local card]
├─ T EN 352 Protection
└─ B Explanation / capability-dependent content

P Bluetooth Control               [bluetooth_control]
└─ C18
   └─ A Disconnect                [16sp Normal accent label]
```

### 17 — Customize Adaptive Audio (`adaptive_audio`; alias `adaptive_strength`)

```text
P Customize Adaptive Audio
├─ SL Customize adaptive audio    [independent control surface]
│  └─ Noise less ↔ noise more / current strength
└─ B Explanation / availability context [13sp; page]
```

### 18 — Equalizer (`equalizer_screen`)

```text
P Equalizer
├─ S Recommended / Custom         [C28 single-choice]
└─ Custom controls                [only when Custom selected]
   ├─ C28 Equalizer graph
   │  └─ V Bass / Mid / Treble drag controls and band labels
   └─ A Reset
```

Graph labels/actions use Material typography roles rather than the explicit SF Pro sizes used by most settings rows. Keep graph values distinct from heading levels.

### 19 — Audio Settings (`audio_settings`)

```text
P Audio Settings
└─ Shared AudioSettingsSection
   ├─ H Audio                     [page]
   └─ C18 Personalized volume / Conversation awareness / Adaptive audio / Equalizer
```

Current source now shares the audio binding with 07; the earlier live audit's contradictory state was from the earlier build. This document records source structure, not a fresh parity test.

### 20 — Automation / Sleep Timer

```text
P Automation                      [smart_automation]
└─ C18
   ├─ t Sleep detection           [capability conditional]
   ├─ t Optimized charging
   ├─ t Resume media after call
   └─ t Speak when battery is low
      └─ S Threshold 10 / 20 / 30 / 40% [conditional nested C28]

P Sleep Timer                     [sleep_timer]
└─ C18
   ├─ A 15 / 30 / 45 / 60 / 90 minute presets [13sp Medium]
   └─ B13 Remaining time + Cancel, or idle status
```

Automation duplicates the functional group inside 05, but uses inline toggles instead of independent cards. Sleep presets are actions; choosing one starts the timer, unlike a passive settings choice.

### 21 — Gym Press Actions (`gym_press_actions`)

```text
P Gym Press Actions
└─ C18
   ├─ Left / Right bud selector
   ├─ B Single press → Play/Pause, fixed [10sp label / 12sp Medium value]
   ├─ Double press × left/right → action dropdown
   ├─ Triple press × left/right → action dropdown
   ├─ Long press × left/right → action dropdown
   └─ A Reset to defaults         [16sp body-style action]
```

Bud-specific “Gym Mode On/Off” mapping is configured through the normal long-press actions; do not assume this screen owns another master toggle.

### 22 — Gym Timer (`gym_timer`)

```text
P Gym Timer
├─ C18 Mode group
│  ├─ H16 Semibold Timer Mode     [inside card]
│  └─ Segmented Countdown / Stopwatch / HIIT [13sp Medium]
├─ C18 Live timer display
│  ├─ V56 Time / countdown
│  ├─ B13 Duration / last lap; V20 phase / round / preparation [mode/state conditional]
│  └─ A Start / Pause / Resume / Cancel, Reset; Lap [16sp Medium]
├─ C18 Laps                       [stopwatch/history conditional]
│  ├─ H16 Semibold Laps
│  └─ Repeated L16 lap + B13 split / elapsed
├─ C18 Duration                   [countdown]
│  ├─ H16 Semibold Duration
│  ├─ B14 Current duration
│  └─ Quick seconds / Minutes / Seconds preset controls [12–13sp values]
├─ C18 HIIT Configuration         [HIIT]
│  ├─ H16 Semibold HIIT Configuration
│  └─ Work / Rest / Rounds with preset values [L16 / B14 / action13]
├─ C18 Announcement preferences
│  ├─ t 5-second start countdown
│  ├─ t Timer announcements
│  ├─ Stopwatch elapsed-time cue selection [mode conditional]
│  ├─ t Intermediate announcements
│  └─ t Final countdown
└─ C18 Display preference
   ├─ t Wake screen and show timer
   └─ B Explanation
```

Mode/state changes which settings and live values appear. Timer speech is independent of notification-announcement speech settings. Current heading placement inside cards differs from ordinary settings categories.

### 23 — Head Tracking (`head_tracking`)

```text
P Head Tracking + toolbar actions
├─ Support/availability content   [conditional]
├─ T Head gestures
├─ T Answer/decline incoming calls
├─ T Mute/unmute during a call
├─ H14 Bold Head Orientation      [page]
│  └─ V Live orientation visualization [C28 graph presentation]
├─ H14 Bold Velocity              [page]
│  └─ V Live velocity plot
├─ T Enable Head Tracking
├─ H14 Bold Advanced Options      [page]
│  └─ T Use alternate head tracking packets
└─ H16 Test Head Gestures          [page]
   ├─ B Shake your head or nod
   └─ V Animated gesture feedback
```

Custom local text uses 14/16/20sp in addition to shared control styles. Functional head-gesture settings and live diagnostics are currently mixed on one page.

### 24 — Find My Case (`proximity_finder`)

```text
P Find My Case + toolbar actions
├─ V Focused signal / warmer-colder visualization [custom hero]
│  ├─ Signal/scan status
│  ├─ Direction / proximity band / RSSI / last-seen metrics
│  └─ B Walking guidance + Got it [dismissible]
├─ A Start/Pause scan; Focus strongest
├─ A Feedback: Sound / Vibration
├─ A Show/Hide signal details
└─ H Nearby Signals
   ├─ Repeated signal surface
   │  ├─ Identity/kind/ownership hint and last-seen status
   │  ├─ Signal strength / band / trend
   │  └─ A Focus / Forget
   └─ B Empty or scan-paused explanation [conditional]
```

This purpose-built visualization uses 11–24sp and 8/12/14/18/28dp radii. These are component variants, not five heading depths. Relative proximity is not a geographic direction or exact distance.

### 25 — Notification Announcements (`notification_announcements?target=…`)

```text
P Notification Announcements
├─ C20 Permissions
│  ├─ H14 Bold Permissions        [inside card]
│  ├─ Notification access status + Grant
│  └─ Phone state/call detection status + Grant
├─ C20 Announcement behavior
│  ├─ t Announce notifications
│  ├─ t Skip during a call
│  ├─ t Skip during active media
│  └─ t Only when AirPods are in ear
├─ H14 Bold Voice engine          [page]
├─ S System TTS / ElevenLabs      [C28]
├─ System voices                  [System engine conditional]
│  ├─ H13 Bold System voices
│  ├─ B13 Explanation
│  └─ Repeated language voice group: H13 language / current voice dropdown / Preview
├─ ElevenLabs configuration       [ElevenLabs engine conditional]
│  ├─ B API-key instructions
│  ├─ Outlined API-key field + Show/Hide
│  ├─ A Test connection + result status
│  └─ H13 Voice + selected voice dropdown / loading / error
├─ H14 Bold Notification content [page]
├─ S Title only / Title and body [C28]
├─ H14 Bold Speech language      [page]
├─ S Automatic detection / System default / each supported language [C28]
├─ N Choose apps → 26             [own navigation surface + B12 footer]
├─ H14 Bold Quiet hours          [page]
├─ C20 t Suppress during quiet hours
├─ H Mode                         [page]
├─ S Manual schedule / Follow system Do Not Disturb [C28]
├─ C20 Start / End time rows → Android time picker [manual schedule]
└─ B Scheduling / DND explanation [conditional]
```

This screen alone mixes C20/C28 and 13/14sp heading roles. API-key fields and provider errors are supporting form content, not independent sections unless we deliberately group them. Permission status and main behavior groups do not currently follow the same heading placement rule.

### 26 — Announcement App Picker (`announcement_app_picker`)

```text
P Apps — disable to silence
├─ Outlined search-apps field     [Material field]
├─ Show system apps control       [14sp label]
└─ App list surfaces              [C20 styling]
   ├─ Repeated app icon + label + inline toggle [L15 / B12 where applicable]
   └─ Loading / empty-state information
```

### 27 — Connection Settings / Connected Devices / Microphone Settings

```text
P Connection Settings             [connection_settings]
└─ ConnectionSettings component  [same functions as 07, own C28 surfaces]
   ├─ Setup explanation
   ├─ Handover switch / peer state / peer chooser or management
   ├─ Auto-connect on case open (last device)
   └─ Automatic ear detection

P Connected Devices               [paired_devices]
├─ B Handover/peer explanation
├─ Repeated C18 peer
│  ├─ L16 Device name + B12/14 status
│  ├─ A Reconnect
│  └─ A Remove → confirmation dialog
└─ A Add peer → bonded-device picker dialog

P Microphone Settings             [microphone_settings]
└─ C28 Custom microphone selector
   └─ L16 Automatic / Always left / Always right
```

Dialogs have title/body/list/actions under platform/Material typography unless locally overridden. They are separate modal surfaces, not extra child cards on the page.

### 28 — Permissions / Battery in Widget / Pop-up Animations

```text
P Permissions                     [permissions]
├─ B14 Introduction / required status
├─ H12 Semibold Standard permissions [page; secondary]
├─ C18 Permission rows
│  ├─ Bluetooth
│  ├─ Location [applicable OS/state]
│  ├─ Notifications
│  └─ Phone
├─ A Grant required permissions
├─ H12 Semibold Special permissions [page; secondary]
├─ Warning/information surface   [14sp warning marker + body]
├─ C18 Special permission rows
│  ├─ Display over other apps
│  └─ Notification access
└─ A Continue                     [onboarding conditional]

P Battery in Widget               [phone_battery]
└─ C18 t Show phone battery in widget

P Pop-up Animations               [popup_animations]
└─ C18
   ├─ t Bottom sheet popup
   └─ t Dynamic Island popup
```

Permission rows use L15 Medium, B12 and action13 Medium rather than standard L16/B13. Permission dialogs are Android-owned; the app cannot impose its card hierarchy on them.

### 29 — Support EveryPods (`purchase_screen`)

```text
P Support EveryPods
├─ C28 Support / thank-you hero
│  ├─ Hero title [up to 22sp]
│  ├─ Body [up to 16sp]
│  └─ A Tip again [post-tip state]
├─ H Choose a tip                 [page]
├─ C28 Tip tier rows
│  └─ Coffee / Beer or snack / Lunch / Big thanks
│     └─ L Tier name + price/status + A Tip
├─ Loading / unavailable / Retry content [conditional]
└─ B Tips are voluntary; all features stay free [page]
```

Local typography spans 13–22sp. Hero typography is an intentional special role, but page navigation and section headings should obey the same ownership rule as settings screens.

### 30 — Email Support (`email_support`)

```text
P Email Support
└─ C18
   ├─ B16 Support address explanation
   └─ A16 Medium Send Email → external composer
```

The Help category launches the composer directly; search can reach this explanatory screen. These are two entry behaviors for the same task.

### 31 — GitHub Issues (`github_issues`)

```text
P GitHub Issues
└─ C18
   ├─ B16 Report bugs / request features explanation
   └─ A16 Medium Open GitHub Issues → external browser
```

### 32 — Open Source Licenses (`open_source_licenses`)

```text
P Open Source Licenses
└─ LibrariesContainer             [third-party AboutLibraries UI]
   └─ Repeated library metadata / license entries and library detail interactions
```

Library/license list and detail styling comes from AboutLibraries and inherited theme roles, rather than the app's custom SF Pro settings rows. It needs an explicit library-surface review; it is not a raw text document.

### 33 — Device Protocol Versions (`version_info`; source `VersionInfoScreen`)

```text
P Device protocol versions
└─ C28 Information presentation
   ├─ Version 1 / corresponding value
   ├─ Version 2 / corresponding value
   └─ Version 3 / corresponding value
```

Local text uses 14/16sp. These are protocol data, not the installed app version. App version belongs to 08's AppInfoCard.

### 34 — Debug / Troubleshooting (`debug`, `troubleshooting` alias)

```text
P Debug
├─ Failure-report surface
│  ├─ L Report lid-open connect failure
│  ├─ B Usage explanation
│  └─ A Export & share report
├─ Diagnostic fields / raw state
├─ Packet log list
│  └─ Packet data → detail/copy interaction
└─ Packet input field + Send
```

Local surfaces use 4/8/12dp; text 12/13/16sp plus defaults. Technical packet/log structure needs its own readable document/data style. Help exposes it only in non-Play builds; both routes are registered.

### 35 — Legacy App Settings source (not the active App Settings page)

```text
P Settings                        [AppSettingsScreen source]
├─ App language setting
├─ H-control Widget → T Show phone battery in widget
├─ T Bottom sheet popup / T Dynamic Island popup
├─ H-control Automatic Ear Detection
│  └─ T Disconnect AirPods when not wearing
├─ Takeover group → Receiving call / Starting media
├─ N Manage permissions
└─ N Email / GitHub issues / Open source licenses + contact sheet
```

The `app_settings` route redirects to 06 and does not render this legacy function. Record it as duplicate source, not a second active App Settings design. It retains 28dp surfaces and local 13/14/16/18sp declarations.

### 36 — Volume Control source (no registered dedicated route)

```text
P Volume Control
├─ H13 Volume swipe speed         [page]
├─ B13 Explanation
└─ C18 → S Swipe-speed choices     [nested C28]
```

`controls_configuration` now opens 03's actual configuration section. This source should not be mistaken for that complete configuration screen.

### 37 — ANC Profiles (dormant source; no registered route)

```text
P ANC profiles
├─ T Auto-ANC profiles
├─ H14 Bold Profiles              [page]
├─ A Add profile
└─ Repeated C20 profile
   ├─ L15 Name + B12 schedule/mode summary
   └─ Open editor dialog
      ├─ Dialog title New/Edit profile
      ├─ Name field
      ├─ Start / End time pickers
      ├─ S Noise-control mode
      └─ Save / Delete / Cancel
```

Do not count this as reachable user functionality until a product/navigation decision is made.

## 5. Auxiliary UI surfaces

These need a coherent component style, but are not additional settings pages.

| Surface | Current functional tree | Styling / containment ownership |
|---|---|---|
| Contact bottom sheet | Sheet title → subject field → description field → send/cancel actions | Own modal surface; shared inputs and local title/body styles; not a card inside Home |
| Stem action dropdown | Current gesture → available actions → selected checkmark | Popup menu, local 15sp items; belongs to its originating row |
| Language/voice dropdown | Current selection → options → selected state | Popup menu; Material defaults or local labels; does not create a page section |
| Peer picker/removal | Dialog title → bonded-device list or confirmation body → confirm/cancel | Dialog surface; Android/Material text defaults and local overrides |
| Time picker | Time value → Android time dialog → confirm/cancel | Android-owned modal |
| Quick Settings dialog | Compact device/status and available device controls | `QuickSettingsDialogActivity`; compact typography/layout, not the page hierarchy |
| Android Quick Settings tiles | AirPods connection tile; Gym mode tile → service actions | Android system tile framework owns overall layout |
| Battery widget | Device/battery information + widget interactions | XML widget layout; compact role system, separate from in-app cards |
| Bottom-sheet connection popup | Device artwork/name/status/battery | Overlay layout (`popup_window.xml`); not a navigation destination |
| Dynamic Island popup | Compact connection/battery state | Overlay layout (`island_window.xml`); constrained-height presentation |
| Timer notifications / lock-screen presentation | Timer state + available timer actions | Android notification/compact presentation; app page provides configuration |
| Permission / battery-optimization requests | Explanation → Android request → result | Android system surface; app owns pre-request explanation and return state |

## 6. Proposed common hierarchy — for review, not implemented by this document

Use **screen → section → settings rows**, with an optional subsection only when the content truly needs one. A single toggle does not need both a section heading and a duplicate label saying the same thing.

```text
Screen
├─ Top app bar: screen title + Back + relevant actions
└─ Scrollable page
   ├─ Section heading                         [outside card]
   ├─ One group surface
   │  ├─ Row: primary label + supporting text + value/control
   │  ├─ Divider
   │  └─ Row: primary label + supporting text + value/control
   ├─ Optional section footer                 [outside card]
   └─ Next section heading + next group surface
```

### Proposed typography and placement contract

These are **custom role names**, not assertions about Material 3's default sizes. If mapped to Material roles, assign the sizes explicitly in the app theme.

| Proposed role | Initial specification | Placement rule |
|---|---|---|
| ScreenTitle | SF Pro 20sp Medium, primary | Top app bar, always outside content cards; accessible full title if truncated |
| SectionTitle | SF Pro 13sp Semibold, semantic secondary color, sentence case | Outside and immediately above its group surface; heading semantics |
| SubsectionTitle | SF Pro 14sp Medium, primary, sentence case | Only for a real named subdivision; prefer separate sections/cards instead of repeatedly nesting |
| SettingLabel / BodyPrimary | SF Pro 16sp Normal, primary | Inside a row/card; not heading semantics |
| BodySupporting | SF Pro 13sp Normal, semantic secondary | Below primary row label **inside** the row when it explains that setting |
| SectionFooter | Same 13sp Normal secondary | **Outside**, below the card when it explains the whole section |
| Value / selection | SF Pro 16sp Normal; selected state uses common accent | Inside row/trailing area or choice group |
| ActionLabel | SF Pro 16sp Medium, sentence case | Button or action row; expand for wrapping; preserve minimum touch area |
| Caption / metadata | SF Pro 12sp Normal | Nonessential metadata only; do not use Light 12sp for essential instructions |
| Hero / live measurement | Named purpose-specific role, e.g. TimerDisplay 56sp | Dedicated display surface; never reused as an arbitrary section heading |
| Error / availability | Supporting role plus semantic color/icon/message | Next to affected setting or in a clear status surface; do not communicate state by opacity alone |

Proposed surfaces: page light `#F2F2F7` / dark black; settings group light white / dark `#1C1C1E`; one 18dp group radius; transparent rows inside it. Use shared color tokens and check contrast instead of making every secondary string an arbitrary opacity. Keep 28dp inputs, hero treatments or special shapes only as explicitly named component variants if we choose to retain them.

Proposed spacing: 16dp page inset; consistent row text alignment; 8dp heading-to-card; 24dp between sections; row minimum 56dp with growth for text; interactive target at least 48dp. These are starting values for review at the Pixel's actual font/display settings, not a claim that fixed heights work at all scales.

### Grouping decisions to make

| ID | Current issue | Suggested decision to review |
|---|---|---|
| G01 | Controls category plus separate accessibility/configuration pages | One canonical Controls grouping; deep links reveal the same section/composable |
| G02 | Conversation awareness in AirPods Settings and Audio; different wrapper styles | Audio owns the full conversation section; Settings may link to it if useful |
| G03 | Adaptive audio in Smart and Audio | Audio owns adaptive-audio tuning; avoid two independent editor presentations |
| G04 | Smart's single large C18 combines gestures, adaptive audio, automation and sleep | Separate sections: Gym, Announcements, Find, Head gestures, Automation, Sleep timer; decide which are links vs inline controls |
| G05 | App Settings groups widget, popups, background access and permissions inside one card | Separate Language, Widget, Connection popups, Background operation, Permissions sections |
| G06 | Audio & Connection mixes microphone, connection and takeover without a clear heading for each | Separate Audio, Microphone, Handover, Automatic connection, Wear detection sections |
| G07 | Notification page mixes permission, behavior, voices and schedule styling | Sections: Access, Announcement behavior, Voice, Content & language, Apps, Quiet hours |
| G08 | Help mixes support and several nested information cards | Separate Support, App information, AirPods information, Diagnostics, Legal sections |
| G09 | Head Tracking mixes user actions and diagnostic graphs | Main “Head gestures” settings; optional Diagnostics/Advanced destination or disclosure |
| G10 | Gym Timer headings are inside cards while other settings headings are external | Keep timer live display special; move configuration section titles outside their cards |
| G11 | Independent controls inside outer cards create card-within-card hierarchy | Container owns surface; child controls render inline rows; one visible group boundary |
| G12 | Mixed capitalization and synonym labels | Sentence case; one canonical visible name shared by title, section/link, and search; aliases only in search |
| G13 | Dormant/duplicate source creates false design scope | Explicitly retain as compatibility, consolidate, or retire; do not expose dormant ANC profiles incidentally |

### Suggested review method

1. Edit the trees first: move a node, rename a group, split or merge cards. Refer to screen number + grouping ID, e.g. “05/G04: move adaptive audio to 07.”
2. Agree on one heading/card rule and the typography table before more cosmetic changes.
3. Mark each canonical functional group and reuse it for category browsing and search destinations.
4. Update the feature/search catalog to match the agreed hierarchy and breadcrumb names.
5. Verify screenshots and navigation after implementation, including conditional content, dialogs, dark theme, long translations and large text. Source structure alone cannot prove visual consistency.

## 7. Review worksheet

| Screen/group | Keep / move / split / merge | New parent or group name | Heading role & outside/inside | Supporting text inside row / footer | Canonical search destination |
|---|---|---|---|---|---|
| 03 Controls | Pending | | | | |
| 04 AirPods Settings | Pending | | | | |
| 05 Smart | Pending | | | | |
| 06 App | Pending | | | | |
| 07 Audio & Connection | Pending | | | | |
| 08 Help | Pending | | | | |
| 22 Gym Timer | Pending | | | | |
| 23 Head gestures/tracking | Pending | | | | |
| 25 Announcements | Pending | | | | |

## 8. Sources and maintenance

Primary navigation: `MainActivity.kt`. Six category trees: `CategoryScreen.kt`. Home/search and menu typography: `AirPodsSettingsScreen.kt`. Common surfaces/text: `StyledScaffold`, `StyledToggle`, `StyledSlider`, `StyledSelectList`, `NavigationButton`. Screen detail sources are indexed below.

Earlier findings: [source/search audit](2026-10-18-ui-search-audit.md), [live audit evidence](2026-10-18-live/README.md). Those files describe earlier snapshots; current route redirects and shared audio bindings supersede some earlier findings. This document is a working-tree snapshot, not a claim that the earlier audit is fully resolved.

### Screen source style index

Sizes/radii below are explicit literals in each source, not semantic levels and not the complete inherited style. “Inherited” means inspect the shared components/Material theme. This index covers all 37 screen files so duplicate/dormant source remains visible.

| Source | Explicit text sizes (sp) | Explicit local corner radii (dp) |
|---|---|---|
| [AccessibilitySettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AccessibilitySettingsScreen.kt) | 12, 16 | 0, 28 |
| [AdaptiveStrengthScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AdaptiveStrengthScreen.kt) | 13, 16 | Inherited / none local |
| [AirPodsSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AirPodsSettingsScreen.kt) | 12, 13, 14, 15, 16, 18, 20, 24 | 0, 10, 14, 16, 18, 20, 22, 24, 27 |
| [AncProfilesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AncProfilesScreen.kt) | 12, 14, 15, 16 | 20 |
| [AnnouncementAppPickerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AnnouncementAppPickerScreen.kt) | 12, 14, 15 | 20 |
| [AppPermissionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AppPermissionsScreen.kt) | 12, 13, 14, 15, 16 | 8, 12, 14, 18 |
| [AppSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AppSettingsScreen.kt) | 13, 14, 16, 18 | 28 |
| [AudioSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/AudioSettingsScreen.kt) | Inherited | Inherited / none local |
| [BluetoothControlScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/BluetoothControlScreen.kt) | 16 | 18 |
| [CallControlsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/CallControlsScreen.kt) | Inherited | Inherited / none local |
| [CategoryScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/CategoryScreen.kt) | 10, 11, 12, 13, 14, 15, 16, 20 | 12, 14, 18 |
| [ConnectionSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ConnectionSettingsScreen.kt) | Inherited | Inherited / none local |
| [ConversationAwarenessScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ConversationAwarenessScreen.kt) | Inherited | 18 |
| [DebugScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/DebugScreen.kt) | 12, 13, 16 | 4, 8, 12 |
| [EmailSupportScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/EmailSupportScreen.kt) | 16 | 18 |
| [EqualizerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/EqualizerScreen.kt) | Inherited | 28 |
| [GitHubIssuesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GitHubIssuesScreen.kt) | 16 | 18 |
| [GymPressActionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GymPressActionsScreen.kt) | 10, 11, 12, 14, 15 | 12, 18 |
| [GymTimerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/GymTimerScreen.kt) | 12, 13, 14, 16, 20, 56 | 8, 10, 12, 18 |
| [HeadTrackingScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/HeadTrackingScreen.kt) | 14, 16, 20 | 28 |
| [HearingProtectionScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/HearingProtectionScreen.kt) | 16 | Inherited / none local |
| [ListeningModeConfigScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ListeningModeConfigScreen.kt) | Inherited | 18 |
| [MicrophoneSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/MicrophoneSettingsScreen.kt) | Inherited | Inherited / none local |
| [NotificationAnnouncementsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/NotificationAnnouncementsScreen.kt) | 12, 13, 14, 15 | 10, 20 |
| [OpenSourceLicensesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/OpenSourceLicensesScreen.kt) | Inherited | Inherited / none local |
| [PairedDevicesScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PairedDevicesScreen.kt) | 12, 14, 16 | 18 |
| [PhoneBatteryScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PhoneBatteryScreen.kt) | Inherited | 18 |
| [PopupAnimationsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PopupAnimationsScreen.kt) | Inherited | 18 |
| [PressActionsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PressActionsScreen.kt) | Inherited | 12, 18 |
| [PressAndHoldSettingsScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PressAndHoldSettingsScreen.kt) | 12, 14, 16 | Inherited / none local |
| [ProximityFinderScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/ProximityFinderScreen.kt) | 11, 12, 13, 14, 15, 16, 17, 18, 20, 24 | 8, 12, 14, 18, 28 |
| [PurchaseScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/PurchaseScreen.kt) | 13, 14, 15, 16, 20, 22 | 28 |
| [RenameScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/RenameScreen.kt) | Inherited | Inherited / none local |
| [SleepTimerScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/SleepTimerScreen.kt) | 13 | 18 |
| [SmartAutomationScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/SmartAutomationScreen.kt) | Inherited | 18 |
| [VersionInfoScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/VersionInfoScreen.kt) | 14, 16 | 28 |
| [VolumeControlScreen](../../android/app/src/main/java/io/automated/ventures/everypods/presentation/screens/VolumeControlScreen.kt) | 13 | 18 |
