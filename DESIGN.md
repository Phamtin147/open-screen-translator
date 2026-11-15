---
name: "Open Screen Translator"
description: "Dual-register Celestial Star Atlas control deck and optical comic overlay system for Android."
colors:
  vermilion-signal: "#c94218"
  vermilion-ink: "#b23510"
  vermilion-blush: "#f7e6df"
  vellum-ground: "#f7f4eb"
  vellum-card: "#ffffff"
  vellum-recessed: "#efece2"
  hairline-vellum: "#d5cebe"
  hairline-bronze: "#b3a892"
  prussian-ink: "#0d192b"
  prussian-slate: "#3d4c63"
  bronze-umber: "#635740"
  on-vermilion: "#ffffff"
  observatory-emerald: "#156834"
  amber-ochre: "#854d0e"
  amber-parchment: "#fef3c7"
  crimson-stop: "#b91c1c"
  crimson-fill: "#b91c1c"
  overlay-vellum: "#faf7ef"
  vermilion-signal-night: "#c93e14"
  vermilion-coral-night: "#ee6d43"
  vermilion-ember-night: "#2d1819"
  observatory-navy-ground: "#081121"
  observatory-navy-card: "#0e1a2e"
  observatory-navy-recessed: "#13223b"
  hairline-orbital-night: "#1e3252"
  hairline-steel-night: "#39537d"
  starlight-ink-night: "#f4f7fb"
  champagne-heading-night: "#e2d9c5"
  starlight-slate-night: "#a6b5cc"
  champagne-muted-night: "#d8c7a8"
  phosphor-mint-night: "#34d399"
  solar-amber-night: "#fbbf24"
  amber-umber-night: "#2c220b"
  coral-danger-night: "#f87171"
  overlay-navy-night: "#091326"
  launcher-navy-ground: "#091629"
typography:
  display:
    fontFamily: "serif"
    fontSize: "20sp"
    fontWeight: 700
    letterSpacing: "0em"
  headline:
    fontFamily: "serif"
    fontSize: "15sp"
    fontWeight: 700
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 700
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "13sp"
    fontWeight: 400
    fontFeature: "tnum"
  label:
    fontFamily: "Roboto, sans-serif"
    fontSize: "11sp"
    fontWeight: 700
    fontFeature: "tnum"
rounded:
  xs: "4dp"
  sm: "8dp"
  md: "10dp"
  lg: "12dp"
  xl: "14dp"
  full: "50%"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "28dp"
  tablet: "64dp"
  wide: "140dp"
components:
  button-primary:
    backgroundColor: "{colors.vermilion-signal}"
    textColor: "{colors.on-vermilion}"
    rounded: "{rounded.lg}"
    height: "52dp"
  button-danger:
    backgroundColor: "{colors.crimson-fill}"
    textColor: "{colors.on-vermilion}"
    rounded: "{rounded.lg}"
    height: "52dp"
  button-outlined:
    backgroundColor: "{colors.vellum-card}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.md}"
    padding: "0dp 12dp"
    height: "48dp"
  card-atlas:
    backgroundColor: "{colors.vellum-card}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.xl}"
    padding: "16dp"
  option-row:
    backgroundColor: "{colors.vellum-recessed}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.md}"
    padding: "10dp 12dp"
    height: "54dp"
  option-row-selected:
    backgroundColor: "{colors.vermilion-blush}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.md}"
    padding: "10dp 12dp"
    height: "54dp"
  input-outlined:
    backgroundColor: "{colors.vellum-recessed}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.md}"
    height: "48dp"
  preview-plate:
    backgroundColor: "{colors.vellum-recessed}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.lg}"
    height: "196dp"
  floating-bubble:
    backgroundColor: "{colors.overlay-navy-night}"
    rounded: "{rounded.full}"
    padding: "13dp"
    size: "52dp"
  floating-bubble-active:
    backgroundColor: "{colors.vermilion-signal}"
    rounded: "{rounded.full}"
    padding: "13dp"
    size: "52dp"
  overlay-card:
    backgroundColor: "{colors.overlay-vellum}"
    textColor: "{colors.prussian-ink}"
    rounded: "{rounded.md}"
    padding: "6dp 9dp"
---

# Design System: Open Screen Translator

## Overview

**Creative North Star: "The Celestial Star Atlas"**

Open Screen Translator treats mobile screen translation and manga bubble reconstruction as an optical instrument rather than a generic dark utility panel. Across both its Material 3 control deck and its system-level floating overlays, surfaces behave like precision astronomical plates: flat, hairline-ruled charts with crisp figure-ground contrast, tabular telemetry readouts, and one reserved signal vermilion channel for live capture and active selection.

The system operates natively in two equal registers tuned to real comic and game reading environments: **Daytime Astronomical Vellum** (`values/colors.xml`) for daylight clarity on warm paper grounds, and **Nighttime Observatory Navy** (`values-night/colors.xml`) for low-glare reading in dark rooms. Motion stays deliberately functional and synchronous—slider drags and reading-direction switches redraw `ComicPreviewView` immediately via `invalidate()`, Material ripples provide native touch feedback, `LinearProgressIndicator` animates determinate GGUF download progress, and the floating crosshair bubble uses a `10dp` drag threshold, immediate loading-state color inversion (`alpha = 0.96f`), and user-calibrated auto-clear timers without decorative screen transitions.

The system explicitly rejects stock dark-glass blur gradients, centered neon-purple pill stacks, drop-shadowed cards, and concealed model or fallback status.

**Key Characteristics:**
- Dual first-class Day (`#F7F4EB` vellum) and Night (`#081121` observatory navy) Material 3 XML registers synchronized across both `MainActivity` and `OverlayManager`.
- Flat `0dp`-elevation outlined chart cards separated strictly by tonal surface steps (`surface_ground` -> `surface_card` -> `surface_recessed`) and `1dp` hairline rules.
- Reserved signal vermilion (`#C94218` Day / `#C93E14` Night) restricted to the primary start-service CTA, active selections, star reticle, and download progress.
- Tabular-numeral telemetry (`fontFeatureSettings="tnum"`) across model sizes, download speeds, and overlay calibration readouts.
- Always-legible optical overlay reticle (`#091326` navy disc with `#D8C7A8` champagne rim) engineered to hold contrast over bright comic pages and dark game HUDs alike.

## Colors

The palette pairs warm Astronomical Vellum surfaces and Prussian ink for daylight legibility with deep Observatory Navy surfaces and starlight/champagne ink for night sessions, governed by a single vermilion signal accent.

### Primary
- **Vermilion Signal** (`#C94218` Day `accent_vermilion` / `#C93E14` Night `accent_vermilion`): Primary start-service button tint (`btnToggleService`), selected engine row and toggle border (`1.5dp`), GGUF download button fill, active `LinearProgressIndicator` bar, `ic_reticle_star.xml` fill, and primary `[01]` badge fill inside `ComicPreviewView`.
- **Vermilion Ink** (`#B23510` Day `accent_vermilion_text` / `#EE6D43` Night `accent_vermilion_text`): High-contrast text accent for inline actions (`Cấp quyền nổi`, `Lấy API Key miễn phí tại aistudio.google.com →`) and the bold `[%02d]` reading-order span (`overlay_badge_bg`) inside live `OverlayManager` translation cards.
- **Vermilion Blush / Ember** (`#F7E6DF` Day `accent_vermilion_soft` / `#2D1819` Night `accent_vermilion_soft`): Soft container tint behind active engine rows (`bg_option_selected.xml`) and checked `MaterialButtonToggleGroup` segments (`selector_toggle_bg.xml`).
- **On Vermilion** (`#FFFFFF` `on_accent` / `overlay_badge_text`): Pure white foreground ink on vermilion buttons and `[01]` preview badges.

### Secondary
- **Bronze Umber / Champagne Muted** (`#635740` Day `ink_muted` / `#D8C7A8` Night `ink_muted`): Tracked secondary role (`colorSecondary`) for uppercase engine status tags (`TẠM DỪNG`, `CHƯA TẢI`, `OFFLINE NHẸ`, `CLOUD AI`, `CÓ MẠNG`), idle status dots (`dot_status_idle.xml`), `tvPreviewSpec` telemetry, and the `1dp` perimeter stroke on dark `OverlayManager` cards (`overlay_card_stroke`) plus the `1.5dp` rim of the idle floating reticle (`bubble_background.xml`).
- **Observatory Emerald / Phosphor Mint** (`#156834` Day `status_active` / `#34D399` Night `status_active`): Active service indicator (`dot_status_active.xml`, `ĐANG CHẠY` badge) and installed/loaded `Hy-MT2-1.8B` status tag (`SẴN SÀNG`, `ĐANG NẠP`).
- **Amber Ochre / Solar Amber** (`#854D0E` Day `status_warning` on `#FEF3C7` `status_warning_surface` / `#FBBF24` Night `status_warning` on `#2C220B` `status_warning_surface`): Missing `SYSTEM_ALERT_WINDOW` permission warning strip (`bg_warning_strip.xml`) and `CHƯA CẤP QUYỀN` badge.
- **Crimson Stop / Coral Danger** (`#B91C1C` shared `status_danger_fill` across Day and Night; `#B91C1C` Day / `#F87171` Night `status_danger`): `status_danger_fill` backs the white-on-crimson `Dừng dịch màn hình` stop CTA (`>= 6.4:1` contrast in both themes), while `status_danger` styles the outlined `Xóa file` GGUF deletion button stroke and label.

### Neutral
- **Ground Canvas** (`#F7F4EB` Day `surface_ground` / `#081121` Night `surface_ground`): Window and scroll background (`android:windowBackground`, `android:colorBackground`).
- **Chart Card Surface** (`#FFFFFF` Day `surface_card` / `#0E1A2E` Night `surface_card`): Outlined `AtlasCardStyle` card containers and unchecked toggle segments.
- **Recessed Plate Surface** (`#EFECE2` Day `surface_recessed` / `#13223B` Night `surface_recessed`): Unselected engine rows (`bg_option_unselected.xml`), dropdown and API key input fills, Hy-MT2 manager drawer, and `ComicPreviewView` background plate (`bg_preview_plate.xml`).
- **Hairline Rules** (`#D5CEBE` Day / `#1E3252` Night `hairline_stroke`; `#B3A892` Day / `#39537D` Night `hairline_strong`): `1dp` card borders, horizontal section dividers, slider inactive tracks, dropdown strokes, preview plate frame, and warning strip border.
- **Primary & Heading Ink** (`#0D192B` Day `ink_primary` / `ink_heading`; `#F4F7FB` Night `ink_primary` and `#E2D9C5` Night `ink_heading`): High-contrast Prussian ink in daylight; crisp Starlight white for body/controls and warm Champagne serif headings at night.
- **Secondary Ink** (`#3D4C63` Day `ink_secondary` / `#A6B5CC` Night `ink_secondary`): Supporting descriptions, active summary line, and model variant explanations.
- **Optical Overlay & Hardware Reticle Surfaces**: Floating cards render `#FAF7EF` (`overlay_card_bg`) with `#0D192B` border/text in Day and `#091326` (`overlay_card_bg`) with `#D8C7A8` border and `#F4F7FB` text in Night at `65%–100%` opacity; the floating bubble remains fixed-contrast `#091326` (idle) or `#C94218` with `#FAF7F0` stroke (active/loading), and the launcher icon background uses `#091629` (`ic_launcher_background`). Note: Shipped resource values (`#635740`, `#C94218`, `#C93E14`, `#EE6D43`, `#B91C1C` `status_danger_fill`) intentionally supersede earlier planning-brief drafts (`#785D38`, `#E85427`) to enforce >= 4.9:1 text and button contrast.

### Named Rules
**The Single Vermilion Signal Rule.** Never spend `accent_vermilion` (`#C94218` Day / `#C93E14` Night) on decorative borders, passive icons, or inactive chrome; reserve it exclusively for the primary service trigger, currently active selections, download progress, and the active reticle state.
**The Dual-Register Parity Rule.** Every surface, hairline, and status role in `values/colors.xml` must maintain a calibrated counterpart in `values-night/colors.xml`, and `OverlayManager` must resolve its `Configuration.uiMode` from `prefs.themeMode` so floating translation cards never invert awkwardly against the user's chosen register.

## Typography

**Display Font:** Android system `serif` (`android:fontFamily="serif"`, bold, resolving to system `Noto Serif` / `Droid Serif`; no custom font binaries are bundled in `res/font/`).
**Body Font:** Android system `Roboto` (`sans-serif` default across `TextView`, `MaterialButton`, `AutoCompleteTextView`, and `OverlayManager`).
**Label/Telemetry Font:** Android system `Roboto` with `android:fontFeatureSettings="tnum"` (`AtlasTabularValue` and status tags), supplemented by `Typeface.MONOSPACE` bold (`10sp`) for schematic reading-order pills inside `ComicPreviewView`.

**Character:** Editorial serif display headers give the control deck an archival star-chart cadence, while native `Roboto` and tabular numerals keep Vietnamese diacritics, byte counts, and floating speech-bubble translations immediately scannable across any system font scale.

### Hierarchy
- **Display** (`700`, `20sp`, default line-height, `0em` tracking): Top app header title (`Open Screen Translator`) in bold system `serif` using `ink_primary`.
- **Headline** (`700`, `15sp` standard / `17sp` hero telemetry headline, `-0.01em` tracking): `AtlasSectionHeading` section titles (`Cặp ngôn ngữ & Bộ dịch AI`, `Chế độ đọc truyện tranh`, `Hiệu chỉnh thẻ dịch nổi`) in bold `serif` using `ink_heading`.
- **Title** (`700`, `14sp` option/switch titles; `15sp` primary CTA `btnToggleService`): Engine names, comic-mode toggle headings, and primary service buttons in bold `Roboto`, sentence case (`textAllCaps="false"`).
- **Body** (`400`, `13sp`, `tnum` on telemetry): Active configuration summary (`EN → VI · Google ML Kit · Manga R→L`), dropdown items, slider labels, API key field, and GGUF status summary; floating translation cards scale dynamically from `10sp` to `24sp` (default `14sp`) with `2dp` extra line spacing and `1.08` line-spacing multiplier.
- **Label** (`700`, `11sp` uppercase tags / `12sp` descriptions, `tnum`): Engine status tags (`CHƯA TẢI`, `OFFLINE NHẸ`, `CLOUD AI`, `CÓ MẠNG`), service status badge (`TẠM DỪNG` / `ĐANG CHẠY`), `tvPreviewSpec` (`R→L · 92% · 14sp`), `689 MB` / `832 MB` / `1.08 GB` quant chips, and `12sp` supporting subtitles (`ink_secondary`).

### Named Rules
**The Tabular Telemetry Rule.** Apply `fontFeatureSettings="tnum"` (`@style/AtlasTabularValue`) to every live readout containing numbers, percentages, megabytes, or `sp`/`s` units (`tvStatusBadge`, `tvActiveSummary`, `tvHymtEngineTag`, `tvDownloadProgressStats`, `tvPreviewSpec`, slider readouts, and `OverlayManager` `[01]` indices) so counters never jitter horizontally while updating.
**The Scalable Type Rule.** Define every user-facing text size in `sp`—never fixed `dp` or `px`—inside unconstrained `wrap_content` containers so layouts never clip when Android system font scaling reaches `1.3x`.

## Layout

`MainActivity` renders as an edge-to-edge (`enableEdgeToEdge()`) single-column `NestedScrollView` whose inner `LinearLayout` (`mainContentContainer`) dynamically merges `WindowInsetsCompat.Type.systemBars() | displayCutout() | ime()` with responsive horizontal padding from `dimens.xml` and base vertical padding (`12dp` top, `28dp` bottom).

- **Responsive Breakpoints (`dimens.xml`):**
  - Compact (`<600dp` phones): `atlas_horizontal_margin = 16dp`, `atlas_preview_height = 196dp`.
  - Medium (`w600dp` foldables & small tablets): `atlas_horizontal_margin = 64dp`, `atlas_preview_height = 212dp`.
  - Expanded (`w840dp` wide tablets & landscape): `atlas_horizontal_margin = 140dp`, `atlas_preview_height = 212dp`.
- **Card & Control Spacing Rhythm:** `16dp` vertical margin below the top header and between stacked cards (`AtlasCardStyle`), `16dp` internal card padding, `12dp` padding inside contextual drawers (`layoutHymtManager`), `8dp` vertical gap between engine rows (`12dp` horizontal / `10dp` vertical row padding), and `8dp` horizontal margin flanking the `48dp×48dp` language swap button (`btnSwapLanguages`).
- **Touch Target Floor:** Interactive controls enforce `minHeight="48dp"` (`btnThemeMode`, `btnGrantOverlay`, language dropdowns, `etGeminiApiKey`, `tvGetApiKey`, `btnDownloadOrLoadModel`, `btnDeleteModel`, `btnDirRtl`/`btnDirLtr`), `52dp` (`btnToggleService`, `layoutComicModeRow`, `layoutWholePageRow`, GGUF variant buttons), or `54dp` (`rowEngine*`). The floating bubble container measures `58dp×58dp` (`3dp` padding around a `52dp` interactive disc).
- **Overlay Viewport Clamping (`OverlayManager.kt`):** Floating cards clamp inside `(8dp side, 24dp top, 48dp bottom)` margins; vertical CJK speech bubbles (`rect.height() > rect.width() * 1.35f`) expand horizontally to `max(rect.width(), 188dp)` (capped at `screenW - 16dp`) and center vertically on `rect.centerY()`, while standard horizontal bubbles enforce a minimum width of `104dp` and minimum estimated height of `36dp`.

### Named Rules
**The 16dp Vertical Axis & 48dp Target Rule.** Align primary card edges on the `16dp` horizontal baseline (expanding via `w600dp`/`w840dp` `dimens.xml` rather than multi-column card grids) and guarantee at least a `48dp` touch target height on every actionable view.

## Elevation & Depth

This system uses zero drop shadows (`cardElevation="0dp"` across `AtlasCardStyle` and flat shape drawables). Visual hierarchy and separation are established exclusively through tonal surface stepping (`surface_ground` -> `surface_card` -> `surface_recessed`) combined with crisp `1dp`–`1.5dp` perimeter rules (`hairline_stroke`, `hairline_strong`, `accent_vermilion`). Above third-party apps, `OverlayManager` establishes depth through Android `WindowManager` `TYPE_APPLICATION_OVERLAY` z-order, translucent backdrop fill (`65%–100%` opacity, default `92%`), and a high-contrast `1dp` perimeter stroke (`overlay_card_stroke`) rather than blurred glass or cast shadows.

### Named Rules
**The Zero-Shadow Chart Rule.** Keep `cardElevation` at `0dp` and separate stacked surfaces with tonal contrast and `1dp` hairline borders instead of Material elevation shadows or dark-glass blur.

## Shapes

Form language pairs gently rounded precision instrument plates (`10dp`–`14dp`) with razor-thin borders and circular optical reticles:

- **Circular / Oval (`50%` / `oval`)**: Top header app icon (`AtlasCircleImage`, `40dp×40dp`), live status dot (`9dp×9dp` `dot_status_active.xml` / `dot_status_idle.xml`), and the floating bubble disc (`52dp` inside `58dp` frame, `bubble_background.xml` / `bubble_background_active.xml` with `1.5dp` stroke).
- **Primary Chart Cards (`14dp` radius, `1dp` stroke)**: `AtlasCardStyle` (`MaterialCardView`) framing the Command Card, Language/Engine Card, and Comic Calibration Card.
- **Containers & Primary Trigger (`12dp` radius)**: Full-width service CTA (`btnToggleService`), `bg_preview_plate.xml` (`1dp` `hairline_strong` stroke), and the `layoutHymtManager` drawer.
- **Controls, Inputs & Overlay Cards (`10dp` radius)**: `btnThemeMode` (`1dp` stroke), `TextInputLayout` boxes, `btnDownloadOrLoadModel`, `btnDeleteModel` (`1dp` stroke), `bg_warning_strip.xml` (`1dp` `hairline_strong` stroke), unselected engine rows (`bg_option_unselected.xml`, `1dp` `hairline_stroke`), selected engine rows (`bg_option_selected.xml`, `1.5dp` `accent_vermilion`), and live `OverlayManager` translation cards (`1dp` `overlay_card_stroke`).
- **Schematic Frames (`8dp` radius) & Micro Pills (`4dp` radius)**: Internal comic panel frames (`1.2dp` stroke) and live sample card (`1.1dp` stroke) inside `ComicPreviewView` (`8dp`, with `10dp` dashed `4dp/4dp` underlying bubble silhouettes); `LinearProgressIndicator` track and `[01]`/`[02]`/`[03]` schematic pills (`4dp`).
- **Adaptive & Monochrome Launcher Icon (`mipmap-anydpi/ic_launcher*.xml`)**: `#091629` solid navy background (`ic_launcher_background.xml`), high-resolution graduated astrolabe ring + dual overlapping cream speech bubbles + upper-right 4-point vermilion star (`drawable-nodpi/ic_launcher_foreground_img.png`), and Android 13+ `#FFFFFFFF` 3-path monochrome vector (`drawable/ic_launcher_monochrome.xml`).

### Named Rules
**The One-Weight Perimeter Rule.** Default borders stay at `1dp` (`hairline_stroke` or `hairline_strong`) and step up to `1.5dp` only for active selection (`bg_option_selected.xml`) or the floating optical reticle (`bubble_background*.xml`).

## Components

### Buttons
- **Shape:** `12dp` radius on the primary full-width service trigger (`btnToggleService`, `minHeight="52dp"`); `10dp` radius on secondary outlined actions (`btnThemeMode`, `btnDeleteModel`, `btnDownloadOrLoadModel`, `minHeight="48dp"`).
- **Primary (`btnToggleService`, `btnDownloadOrLoadModel`):** Idle/ready uses `accent_vermilion` (`#C94218` Day / `#C93E14` Night) with `#FFFFFF` bold text (`15sp` / `13sp`); running `btnToggleService` switches `backgroundTintList` to `status_danger_fill` (`#B91C1C`) and label to `Dừng dịch màn hình`.
- **Outlined & Text Actions:** `btnThemeMode` (`1dp` `hairline_strong` stroke, `16dp` `ic_theme_mode` icon), `btnDeleteModel` (`1dp` `status_danger` stroke/text), `btnGrantOverlay` (TextButton inside warning strip, `accent_vermilion_text`), and `btnSwapLanguages` (`48dp×48dp` borderless ripple ImageButton with `ic_swap_horiz`).

### Cards / Containers
- **Corner Style:** `14dp` outer `MaterialCardView` (`AtlasCardStyle`); `12dp` inner drawer/preview container (`bg_preview_plate.xml`).
- **Background:** `surface_card` (`#FFFFFF` Day / `#0E1A2E` Night) for cards; `surface_recessed` (`#EFECE2` Day / `#13223B` Night) for inner drawers and plates.
- **Shadow Strategy:** `0dp` elevation; `1dp` `hairline_stroke` / `hairline_strong` perimeter rule.
- **Internal Padding:** `16dp` on top-level cards, `12dp` on `layoutHymtManager`.

### Inputs / Fields
- **Style:** `Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu` (`tilSourceLang`, `tilTargetLang`) and `OutlinedBox` (`etGeminiApiKey` with `password_toggle`) using `surface_recessed` fill, `10dp` corners on all four vertices, `hairline_strong` stroke, `13sp` `ink_primary` text, and `minHeight="48dp"`.

### Selectable Engine Rows & Toggles
- **Engine Deck (`rowEngine*`):** `54dp` min-height horizontal rows (`12dp` horizontal / `10dp` vertical padding, `8dp` bottom gap) alternating between `bg_option_unselected.xml` (`surface_recessed`, `1dp` `hairline_stroke`) and `bg_option_selected.xml` (`accent_vermilion_soft`, `1.5dp` `accent_vermilion`), with `?attr/selectableItemBackground` ripple and right-aligned `11sp` bold uppercase status tag. Selecting `Hy-MT2-1.8B` reveals `layoutHymtManager` (3-way `MaterialButtonToggleGroup` for `IQ2_M 689 MB` / `Q3_K_S 832 MB` / `Q4_K_M 1.08 GB` + determinate `6dp`-thick `LinearProgressIndicator`); selecting `Gemini AI` reveals `layoutGeminiConfig`.
- **Switches & Direction Segmented Toggle:** `MaterialSwitch` inside `52dp`-tall clickable rows (`layoutComicModeRow`, `layoutWholePageRow`) separated by a `1dp` `hairline_stroke` rule; `toggleReadingDirection` uses `selector_toggle_bg.xml` (`accent_vermilion_soft` checked / `surface_card` unchecked) and `selector_toggle_stroke.xml` (`accent_vermilion` checked / `hairline_strong` unchecked).
- **Calibration Sliders:** Three Material `Slider` controls (`Opacity` 65–100 step 1, `TextSize` 10–24 step 1, `AutoClear` 0–30 step 5) with `labelBehavior="gone"`, `ink_primary` thumb and active track, `hairline_stroke` inactive track, and right-aligned `AtlasTabularValue` readouts.

### Signature Component: `ComicPreviewView` (*Bubble Order & Calibration Plate*)
- **Role & Geometry:** Custom Android `View` (`196dp` height on phones, `212dp` on `>=600dp`) drawn over `bg_preview_plate.xml` with zero `onDraw` allocations (preallocated `RectF` fields). Renders three `8dp`-rounded comic panel frames (`1.2dp` `hairline_strong`), a dashed `6dp` meridian reticle tick, and three `10dp`-rounded dashed speech-bubble silhouettes (`bLeft`, `bRightTop`, `bRightBot`).
- **Live Calibration & Interaction:** Toggling `Manga (Phải → Trái)` vs `Webtoon (Trái → Phải)` dynamically reassigns `[01]`, `[02]`, `[03]` order badges (`4dp` pills, `10sp` `Typeface.MONOSPACE` bold; active `[01]` uses `accent_vermilion` + `#FFFFFF` while secondary `[02]`/`[03]` use `surface_recessed` + `1.2dp` `hairline_strong`); turning `switchComicMode` off switches badges to `A`/`B`/`C` and inserts a mid-sentence line break. Dragging Opacity or Text Size sliders synchronously updates `overlayOpacityPercent` (`65%–100%`) and `previewTextSizeSp` (`10sp–24sp`) on the live `StaticLayout` sample card via `invalidate()`.

### Floating Overlay System (`layout_floating_bubble.xml` & `OverlayManager.kt`)
- **Floating Crosshair Reticle:** `58dp×58dp` overlay container (`3dp` padding around a `52dp` `ImageButton` with `13dp` icon padding), anchored at `(24px, 320px)` via `TYPE_APPLICATION_OVERLAY` (`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`), supporting drag vs click with a `10dp` (`clickThreshold`) guard. Idle state renders `bubble_background.xml` (`#091326`, `1.5dp` `#D8C7A8`) + `ic_translate.xml` (`#FAF7F0` viewfinder + `#E85427` 4-point star); active capture/translation swaps to `bubble_background_active.xml` (`#C94218`, `1.5dp` `#FAF7F0`) + `ic_translate_active.xml` (`#FFFFFF`) at `alpha = 0.96f`.
- **Floating Translation Cards:** Programmatic `TextView` overlays (`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`) using `GradientDrawable` (`10dp` radius, `1dp` `overlay_card_stroke`, `overlay_card_bg` at calibrated alpha, `9dp` horizontal / `6dp` vertical padding), `fontFeatureSettings = "tnum"`, `Gravity.CENTER`, and `2dp` + `1.08` line spacing. When multi-bubble comic indexing is active, `[%02d] ` renders bold at `0.84f` relative size in `overlay_badge_bg`. Single-tap clears all cards; long-press removes the tapped card; `autoClearSeconds > 0` schedules `autoClearRunnable`.

## Do's and Don'ts

### Do:
- **Do** keep every card at `cardElevation="0dp"` (`AtlasCardStyle`) and express hierarchy through `surface_ground`, `surface_card`, `surface_recessed`, and `1dp` hairline rules.
- **Do** declare every text size in `sp`, enforce `minHeight="48dp"` on all interactive controls, and apply `fontFeatureSettings="tnum"` to numeric telemetry and readouts.
- **Do** resolve `OverlayManager` theme resources through a `Configuration`-adjusted context matching `prefs.themeMode` (`Ban ngày` / `Ban đêm` / `Tự động`) so floating cards mirror the user's chosen Day or Night register.
- **Do** keep `status_danger_fill` (`#B91C1C`) behind white `on_accent` stop-button text in both Day and Night modes while reserving Night `status_danger` (`#F87171`) strictly for dark-surface outlined strokes and text.
- **Do** surface engine readiness, GGUF download progress (`MB`, `MB/s`, `%`), and active reading mode (`R→L` / `L→R`) directly in the Command Card and Engine Deck without hiding fallback or unready states.

### Don't:
- **Don't** introduce dark-glass backdrop blurs, drop shadows, neon glow borders, or purple gradient pills on either `MainActivity` or `OverlayManager` cards.
- **Don't** spend `accent_vermilion` (`#C94218` Day / `#C93E14` Night) on neutral icons, idle cards, or more than one primary service CTA per viewport.
- **Don't** hardcode fixed `px` or `dp` text sizes or clip `TextView` containers that break at `1.3x` system font scale.
- **Don't** allocate `Paint`, `RectF`, or `DashPathEffect` objects inside `ComicPreviewView.onDraw`, which redraws synchronously on every slider tick.
- **Don't** ship raw emoji or text glyphs as UI icons; draw vector drawables in `res/drawable/*.xml` matching the `1.8dp`–`1.9dp` stroke grammar.
