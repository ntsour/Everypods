

## 1. Brand direction — "PRO"

**Concept**: ProPods is the pro-tools version of AirPods on Android — every setting that Apple buried is now front-and-center, plus features Apple never shipped. The visual language borrows from **pro-audio gear** (studio monitors, broadcast headphones, modular synth UIs): matte black surfaces, a single warm accent that reads as "active / powered on," tight geometric type, no decoration that isn't functional.

Think Teenage Engineering meets Sennheiser HD-series, not Apple keynote.

### Palette (locked)
- **Primary surface**: `#0E0E10` — near-black, slightly warm (not pure #000)
- **Secondary surface**: `#1C1C20` — for layering / depth
- **Accent (signal orange)**: `#FF6A1F` — warm orange-amber, the "powered on" LED color of pro gear
- **Highlight white**: `#F2EEE5` — bone, slightly warm; never pure white
- **Muted line / grid**: `#3A3A40` — for fine detail and technical drawing lines

### Style words
*Geometric, precise, instrumented, weighty, tactile, minimal, signal-orange-on-matte-black, machined, broadcast-grade, fader-and-knob aesthetic.*

### What this rules OUT
No gradients (or very subtle, ≤3% luminance shift). No glow. No glassmorphism. No iOS-style soft drop shadows. No skeuomorphic textures. No cartoony illustrations. Sharp corners or precise small radii only.

> Every prompt below has `{BRAND}`, `{PALETTE}`, `{STYLE}` pre-filled with the values above.

---

## 2. App launcher icon

Android adaptive icon = 3 layers (foreground, background, monochrome). Generate the foreground as a 432×432 PNG with a transparent background, keeping the meaningful content inside the central 264×264 safe zone (the OS crops/masks the outer ring on different devices).

### 2a. Foreground layer
**Output**: 432×432 PNG, transparent background, content centered in 264×264 safe zone.
**Files it replaces**:
- `android/app/src/main/res/drawable-v24/ic_launcher_foreground.xml` → replace with a PNG-backed `<bitmap>` or convert to vector via SVG → Android Studio's "Asset Studio."

**AI prompt**:
```
A minimalist Android app launcher icon foreground, "PRO" brand — pro-audio
gear inspired. Single subject: a stylized AirPods-Pro-style wireless earbud
rendered as a precise geometric form, near-black body (#0E0E10) with a
single small signal-orange (#FF6A1F) detail on the stem reading as a "power
on" indicator LED. Faint machined-aluminum highlight on one edge in bone
white (#F2EEE5). The earbud is centered, facing slightly forward-left, stem
angled down at ~15°. Strong silhouette readable at 48×48 pixels.
NO text, NO words, NO logos, NO Apple branding, NO Apple "AirPods" wordmark,
NO gradients beyond a single ≤3% luminance shift. Transparent background.
Symmetric, balanced composition, content fits inside the central 60% of the
canvas (Android adaptive icon safe zone). Crisp edges, no photographic noise,
no drop shadow, no glow. Style: geometric, precise, machined, broadcast-grade,
weighty. Flat illustration with subtle CAD-like edge highlights only.
1024×1024 PNG master, transparent background.
```

### 2b. Background layer
**Output**: 432×432 solid color OR very subtle pattern. Will be cropped to any shape (circle, squircle, rounded square).
**File**: `drawable-v24/ic_launcher_background.xml` — easiest to keep as a vector `<shape>` with a single color or 2-stop gradient.

**Spec**: Solid `#0E0E10` (primary surface). Optionally a *very* subtle 2-stop linear gradient from `#0E0E10` (top-left) to `#1C1C20` (bottom-right) at 135° — keep the luminance shift ≤3% so it still reads as flat matte black on small icons. Do **not** put any content or marks in this layer; the launcher OS may crop it to any shape.

Define as a vector drawable:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="135" android:startColor="#0E0E10" android:endColor="#1C1C20" />
</shape>
```

### 2c. Monochrome layer (Android 13+ themed icons)
**Output**: 432×432, content in alpha only, **single color** (Android tints it). Must be the same silhouette as the foreground, but rendered as a single-color mask.
**File**: `drawable-v24/ic_launcher_monochrome.xml`

**AI prompt**:
```
The same earbud silhouette as the foreground icon, but rendered as a flat
single-color mask in pure white on transparent background. No gradients, no
inner detail, no shading — just the cleanest possible silhouette readable
at 48×48px. 1024×1024 PNG, alpha channel only.
```

---

## 3. Notification & status bar icons

Android requires status-bar icons to be **white silhouettes on transparent backgrounds** — the OS tints them. Any color or gradient will render as a solid white blob. There are three of these to replace:

- `drawable/airpods_pro_left_notification.xml`
- `drawable/airpods_pro_right_notification.xml`
- `drawable/airpods_pro_case_notification.xml`

### Spec
- **Output**: 24×24 dp vector (SVG → import via Android Studio Asset Studio → produces VectorDrawable XML).
- **Format**: pure white (`#FFFFFFFF`) on transparent. **No color, no gradients, no antialiased shadows.**
- **Density**: shape must be recognizable at 24×24px — strip all detail, keep silhouette only.

### AI prompt — Left earbud
```
A pure-white silhouette icon on transparent background, 1024×1024 master
designed to downsize cleanly to 24×24. Subject: a single AirPods-Pro-style
wireless earbud, stem pointing straight down, slightly tilted left at ~10°,
body and stem joined as one continuous silhouette. Single solid white
(#FFFFFF) shape, no gradient, no antialiasing artifacts, no inner detail,
no eartip texture. Bold readable silhouette at 24×24 px. Centered, ~70%
of canvas. Geometric, flat, Material Design 3 notification-icon style.
Sharp clean curves, no fuzzy edges. NO text, NO color, NO drop shadow,
NO inner cut-outs, NO mesh details.
```

### AI prompt — Right earbud
```
A pure-white silhouette icon on transparent background, 1024×1024 master
designed to downsize cleanly to 24×24. Subject: a single AirPods-Pro-style
wireless earbud — MIRRORED version of the left earbud icon: stem pointing
straight down, slightly tilted right at ~10°. Single solid white (#FFFFFF)
shape, no gradient, no antialiasing artifacts, no inner detail. Bold
readable silhouette at 24×24 px. Centered, ~70% of canvas. Geometric, flat,
Material Design 3 notification-icon style. NO text, NO color, NO drop shadow.
```

### AI prompt — Case
```
A pure-white silhouette icon on transparent background, 1024×1024 master
designed to downsize cleanly to 24×24. Subject: an open AirPods-Pro-style
charging case viewed straight-on from the front, lid open and raised at
~70°, two small earbud tops just visible inside the case opening as small
notches in the silhouette. Single solid white (#FFFFFF) shape, no gradient,
no antialiasing artifacts, no inner detail beyond the two notches indicating
the earbuds. Bold readable silhouette at 24×24 px. Centered, ~70% of canvas.
Geometric, flat, Material Design 3 notification-icon style. NO text,
NO color, NO drop shadow, NO hinge line, NO LED dot.
```

> After generation, post-process in Figma or Inkscape: trace bitmap → expand to path → export SVG → import to Android Studio (`File → New → Vector Asset`).

---

## 4. Splash / branding

The app currently uses the system default splash (no custom theme found). Adding a proper splash via Android 12's `SplashScreen` API will make a strong first impression.

### 4a. Splash icon
**Output**: 432×432 PNG, transparent background.
**Spec**:
- The same earbud subject as the launcher icon foreground, but **simplified** (splash icons should be even more iconic than launcher).
- Solid color or 2-tone — no gradients (the splash screen API doesn't handle gradients well at all sizes).

**Wiring** (after image is ready):
1. Save as `drawable/splash_icon.xml` (vector) or `drawable-xxxhdpi/splash_icon.png`.
2. Add to `values/themes.xml`:
   ```xml
   <style name="Theme.ProPods.Splash" parent="Theme.SplashScreen">
       <item name="windowSplashScreenBackground">@color/splash_background</item>
       <item name="windowSplashScreenAnimatedIcon">@drawable/splash_icon</item>
       <item name="postSplashScreenTheme">@style/Theme.ProPods</item>
   </style>
   ```
3. Set `Theme.ProPods.Splash` as `MainActivity`'s theme in the manifest.

**AI prompt**:
```
A single iconic AirPods-Pro-style wireless earbud silhouette, centered on
transparent background. "PRO" brand — pro-audio gear inspired, extremely
simplified because this splash icon is shown for under a second and must
read instantly. The earbud body is filled solid bone-white (#F2EEE5) — this
is the splash icon on a dark background, so the SUBJECT itself is light.
One single small signal-orange (#FF6A1F) dot near the top of the stem reading
as a "power on" indicator LED — this is the ONLY non-white element in the
icon. Stem points down, body slightly tilted forward-left. No internal
detail beyond that one LED dot. 1024×1024 PNG, transparent background.
NO text, NO logo, NO wordmark, NO drop shadow, NO gradient, NO glow.
Sharp geometric edges, machined precision feel.
```

> Splash background color: `#0E0E10` (define as `<color name="splash_background">#0E0E10</color>` in `values/colors.xml`).

### 4b. About-screen wordmark
**Output**: SVG, 800×200 master.
**Spec**: The text "ProPods" rendered as a custom wordmark, paired with a small mark (a tiny version of the launcher silhouette) to the left.
- **Font (locked)**: **JetBrains Mono** (free, OFL) — weight 700 for "Pro", weight 400 for "Pods". The mono / technical typeface reinforces the "pro tools" feel. Alternative: **Space Grotesk** 700 if you want less technical, more product-design feel.
- **Layout**: "Pro" in `#F2EEE5` (bone) + "Pods" in `#FF6A1F` (signal orange). Single line, left-aligned. Letter-spacing -2%.
- **Mark**: a small 40×40 version of the launcher silhouette to the left of the text, 16px gap.

**AI prompt** (text generation in image AI is unreliable — do this in Figma or hand-build in SVG):
```
A clean wordmark for an Android utility app called "ProPods". Single line,
left-aligned. JetBrains Mono 700 for "Pro" in bone white #F2EEE5, JetBrains
Mono 400 for "Pods" in signal orange #FF6A1F, letter-spacing -2%, baseline
aligned. To the left of the text with a 16px gap, a small 40×40 silhouette
mark — a wireless earbud body in #F2EEE5 with one tiny #FF6A1F LED dot on
the stem (matching the launcher icon exactly). Transparent background.
800×200 SVG. NO drop shadow, NO underline, NO tagline.
```

**Hand-built SVG fallback** (drop into `drawable/wordmark.xml` after converting via Android Studio):
```svg
<svg width="800" height="200" viewBox="0 0 800 200" xmlns="http://www.w3.org/2000/svg">
  <!-- mark -->
  <g transform="translate(40 80)">
    <!-- replace with actual earbud silhouette path -->
    <path d="M20 0 C8 0 0 12 0 26 V50 C0 64 8 76 20 76 C32 76 40 64 40 50 V26 C40 12 32 0 20 0 Z" fill="#F2EEE5"/>
    <circle cx="20" cy="18" r="3" fill="#FF6A1F"/>
  </g>
  <!-- wordmark -->
  <text x="96" y="130" font-family="JetBrains Mono" font-weight="700" font-size="96" fill="#F2EEE5" letter-spacing="-2">Pro<tspan font-weight="400" fill="#FF6A1F">Pods</tspan></text>
</svg>
```

---

## 5. Google Play Store listing assets

Required by Play Console for a public listing. Exact dimensions are non-negotiable.

| Asset                   | Size (px)     | Format     | Notes |
|-------------------------|---------------|------------|-------|
| **App icon**            | 512 × 512     | 32-bit PNG | Same as launcher 2a, exported at 512px. No transparency. |
| **Feature graphic**     | 1024 × 500    | JPG/PNG    | Hero banner shown at top of listing. No transparency. **Must not contain the app icon** (Play guideline) — show the *value*, not the brand. |
| **Phone screenshots**   | min 1080 × 1920 | PNG/JPG  | 2–8 screenshots. Use real device screenshots, no marketing copy overlay required (but allowed). |
| **7-inch tablet**       | 1080 × 1920+  | PNG/JPG    | Optional but recommended. |
| **10-inch tablet**      | 1920 × 1200+  | PNG/JPG    | Optional. |
| **Promo video**         | YouTube URL   | —          | Optional, 30s ideal. |

### 5a. App icon (512×512)
Export the launcher foreground (§2a) at 512×512 with a **non-transparent** background (use the background color from §2b). Play Console rejects transparent PNGs for the app icon slot.

### 5b. Feature graphic prompt
**Output**: 1024×500 JPG, full bleed, **opaque** (Play Console rejects transparency here).
```
A wide horizontal hero banner, 1024×500 px, for an Android utility app
called ProPods that brings pro-grade AirPods features to Android. "PRO"
brand — pro-audio gear inspired, broadcast-grade aesthetic.

Background: matte near-black (#0E0E10) with a *very* subtle warm vignette
darkening the edges. Faint horizontal scan lines or a near-invisible
technical grid in #3A3A40 across the entire surface — must be subtle enough
not to compete with the subject.

Composition: a single AirPods-Pro-style wireless earbud occupying the LEFT
THIRD of the frame, rendered in machined-aluminum / matte-black materials,
dramatic single-source side light from the upper-right creating a sharp
specular highlight along the body edge. The earbud has one small glowing
signal-orange (#FF6A1F) LED dot on the stem — the only saturated color
anywhere in the image. Subtle shallow depth-of-field.

The RIGHT TWO-THIRDS is intentionally quieter and darker: a soft suggestion
of physical knobs, faders, or fine-pitch UI controls fading into the
darkness — abstracted, out of focus, never readable. The overall mood is
premium broadcast/studio gear, not consumer-flashy.

NO TEXT, NO LOGOS, NO WORDS, NO numbers anywhere in the image (Google Play
will overlay the app title). Cinematic studio product photography style,
not cartoony, not illustrated. 2048×1000 master, downsize to 1024×500.
```

> Generate at 2048×1000 master, downsize to 1024×500.

### 5c. Phone screenshots
Best done by hand from a real device, then optionally overlaid with marketing copy in Figma. Suggested 5 screenshots (in order):
1. **Hero / main screen** — buds connected, battery percentages visible. Caption: *"Native AirPods features, on Android."*
2. **Conversation Awareness** — the new sub-screen with master toggle. Caption: *"Hear and be heard, without lifting a finger."*
3. **Smart features list** — Gym Mode, sleep timer, battery alerts. Caption: *"Smart features Apple never shipped here."*
4. **Customizable controls** — stem press mapping. Caption: *"Map every press, your way."*
5. **Search & settings** — the searchable settings UI. Caption: *"Find anything in one tap."*

Capture via `adb exec-out screencap -p > shot.png`. Then in Figma:
- Drop into a 1080×1920 frame.
- Add a top bar with the caption in your brand font.
- Export PNG.

---

## 6. File checklist (after generation)

Put everything into `design/assets/` (this folder), then wire to Android resources:

```
design/
  asset-brief.md                          ← this file
  assets/
    launcher_foreground.png               (432×432, transparent)
    launcher_foreground_master.png        (1024×1024, transparent)
    launcher_monochrome.png               (432×432, white alpha)
    launcher_background_color.txt         (single hex)
    notification_left.svg                 (24dp, white alpha)
    notification_right.svg
    notification_case.svg
    splash_icon.png                       (432×432)
    splash_background_color.txt
    wordmark.svg                          (800×200)
    play_app_icon.png                     (512×512, opaque)
    play_feature_graphic.jpg              (1024×500)
    play_screenshots/
      01_hero.png ... 05_search.png       (1080×1920)
```

Then I'll wire them into the project:
- Replace the 3 launcher vector XMLs (`drawable-v24/ic_launcher_*.xml`) with the new ones (or convert PNG→Vector via Android Studio's *File → New → Vector Asset*).
- Replace the 3 notification vector XMLs.
- Add `splash_icon` and splash theme to `values/themes.xml`.
- Add wordmark to the About screen.

---

## 7. Suggested generation workflow

1. Pick brand direction (A / B / C) — tell me.
2. Run the **launcher foreground** prompt first. Iterate until the silhouette reads at 48px (zoom out in your image tool to check).
3. Once locked, use the same subject across §3, §4, §5 — *exact same earbud shape*, just different framing. Consistency > novelty.
4. The feature graphic is the only asset where you can be more cinematic — treat it as the "ad."
5. Send me the files and I'll wire them.
