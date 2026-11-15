---
version: 1
slug: "app-src-main-res-layout-activity-main-xml"
primary_target: "app/src/main/res/layout/activity_main.xml"
related_targets: ["app/src/main/java/com/openscreentranslator/app/ui/MainActivity.kt","app/src/main/java/com/openscreentranslator/app/overlay/OverlayManager.kt","app/src/main/res/layout/layout_floating_bubble.xml"]
---

# Surface Brief: Main Control Deck & Overlay System

## Direction contract

- **THESIS:** Replace the generic dark utility stack with a Dual-Register Celestial Star Atlas (*Daytime Astronomical Vellum* + *Nighttime Observatory Navy*) where translation bubbles, engine state, and speech-bubble reading coordinates behave like precision optical overlays rather than neon-bordered boxes. Refuses stock dark-glass gradients, centered purple pill stacks, and hidden model/fallback state.
- **OWN-WORLD:** Day: `#F7F4EB` star-plate vellum ground, `#FFFFFF` / `#EFECE2` chart cards, `#0D192B` Prussian ink, `#785D38` tracked bronze uppercase labels, `#D5CEBE` 1dp hairline chart rules. Night: `#081121` deep navy ground, `#0E1A2E` observatory surface, `#F4F7FB` starlight body ink, `#E2D9C5` champagne display headers, `#1E3252` 1dp orbital rules. Single active signal vermilion (`#C94218` day / `#E85427` night) strictly on the primary start/stop service action, active reticle indicator, and active download/running telemetry. Raised discipline from declined challengers: Ikeda tabular numeric columns (`tnum` for MB/GB, %, sp) and Crouwel 16dp vertical alignment axis.
- **STORY:** On launch, the reader immediately checks permissions, engine readiness (whether `Hy-MT2-1.8B` GGUF is downloaded or `ML Kit` / `Gemini` is active), taps one vermilion button to launch the floating crosshair bubble, configures `Manga R→L` vs `Webtoon L→R` bubble merging, and calibrates live translation card readability before jumping into their comic or game.
- **FIRST VIEWPORT:** Edge-to-edge top bar (48dp touch targets) with serif display title *Open Screen Translator* and quick Light/Dark/Auto theme toggle; immediately below sits the Celestial Command Card (live permission/service status, tabular engine metrics, and full-width 52dp signal-vermilion `Bật dịch màn hình` button), followed by the paired Language Switcher (`Nguồn ⇄ Đích`) and Engine Deck (`Hy-MT2-1.8B` / `ML Kit` / `Gemini AI` / `Cloud Free`).
- **NAMED SIGNATURE INTERACTION:** Interactive *Bubble Order & Calibration Plate* on the control screen—toggling `Manga R→L` vs `Webtoon L→R` visibly re-numbers `[01] [02] [03]` sample speech bubbles in real time while the Opacity, Text Size, and Auto-Clear sliders dynamically restyle the live preview translation card exactly as it will render over pages.
- **FORM:** Challenger `Celestial Star Atlas` (`notation-diagram-systems-star-atlas`, chosen by user on code-led Operate build path). `SEED REFERENCE: seed:bae8d435536e07d3`.
- **FINISH:** unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Scope & Mode

- Mode: Operate (mobile Android utility + overlay reader).
- Primary targets:
  - `app/src/main/res/layout/activity_main.xml`
  - `app/src/main/res/layout/layout_floating_bubble.xml`
  - `app/src/main/java/com/openscreentranslator/app/ui/MainActivity.kt`
  - `app/src/main/java/com/openscreentranslator/app/overlay/OverlayManager.kt`
  - `app/src/main/res/values/themes.xml`
  - `app/src/main/res/values-night/themes.xml`
