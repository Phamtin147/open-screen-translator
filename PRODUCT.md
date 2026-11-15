# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Vietnamese mobile readers and gamers reading raw or English-scanlated manga, manhua, manhwa, webtoons, and playing foreign-language games on Android phones and tablets, in both daylight and low-light environments.

## Product Purpose

Provide instant, unlimited, ad-free on-screen translation through a floating overlay bubble so users can translate speech bubbles and game dialogue in place without leaving the app they are reading or playing.

## Positioning

Unlike commercial screen translators that enforce daily quotas, show ads, or send screenshots to opaque third-party servers, Open Screen Translator runs entirely on open architecture with four selectable engines: on-device Hy-MT2-1.8B GGUF (via `llama.cpp`), Google ML Kit offline, Google Gemini AI (bring-your-own API key), and Google Translate Cloud Free—plus manga/comic-aware speech bubble reconstruction and full-page context translation.

## Operating Context

- Used on top of manga reader apps, browsers, and games via Android `MediaProjection` + `TYPE_APPLICATION_OVERLAY` floating bubble.
- Used across both bright daytime and dark nighttime lighting; both Light and Dark Material 3 themes are first-class operating modes.
- Comic pages frequently contain multi-line wrapped speech bubbles and multiple ordered bubbles per page (Japanese manga right-to-left or comic/webtoon left-to-right), requiring spatial grouping and reading-order context.
- Local LLM execution (`Hy-MT2-1.8B` GGUF, 689 MB – 1.08 GB) downloads once inside the app from Hugging Face and runs offline without network access.

## Capabilities and Constraints

- Supported languages: Automatic multi-language per-bubble detection (`auto` — simultaneous Latin + Japanese + Chinese + Korean OCR & script/ML Kit identification on the same page), plus 15 OCR source languages (`en`, `ja`, `zh`, `ko`, `fr`, `de`, `es`, `pt`, `id`, `it`, `nl`, `pl`, `tr`, `vi`) and 17 target languages (adding `ru`, `th`, `ar`).
- Engines:
  1. `hy_mt2`: On-device `tencent/Hy-MT2-1.8B` / `unsloth/Hy-MT2-1.8B-GGUF` running via `llama.cpp` JNI (ARM64/NEON). Requires downloading one GGUF quant file in-app (`UD-IQ2_M` ~689 MB, `Q3_K_S` ~832 MB, or `Q4_K_M` ~1.08 GB) before offline inference.
  2. `on_device`: Google ML Kit On-Device Translation (`com.google.mlkit:translate:17.0.2`), auto-downloading language packs on first use and falling back to Cloud Free on failure.
  3. `gemini_ai`: Google Gemini API using the user's own API key from Google AI Studio (`aistudio.google.com`).
  4. `cloud_free`: Keyless Google Translate HTTP fallback endpoint.
- Comic Reading Mode:
  - Toggleable Reading Direction: Manga (Right-to-Left, Top-to-Bottom) vs Comic/Webtoon (Left-to-Right, Top-to-Bottom).
  - Multi-line & split-bubble merging within speech-bubble spatial boundaries, CJK vertical/horizontal text cleanup, and whole-page contextual batching for LLM engines (`hy_mt2` and `gemini_ai`).
- Must compile cleanly with Android SDK 34, minSdk 26, Gradle 8.7, NDK 27/28, and honor system overlay, notification, and media-projection permissions as well as font scaling.

## Brand Commitments

- Product name: Open Screen Translator (`com.openscreentranslator.app`).
- Vietnamese-first UI copy that is crisp, practical, and free of ads or promotional bloat.
- Includes a newly crafted, recognizable app icon replacing the placeholder raster launcher icon.

## Evidence on Hand

- Existing Android codebase at `/home/amtia/Projects/open-screen-translator` (`MainActivity`, `FloatingBubbleService`, `OcrManager`, `TranslationManager`, `OverlayManager`, `AppPreferences`).
- Verified Hugging Face model sources: `tencent/Hy-MT2-1.8B-GGUF` (`Hy-MT2-1.8B-Q4_K_M.gguf`, 1080.6 MB) and `unsloth/Hy-MT2-1.8B-GGUF` (`Hy-MT2-1.8B-UD-IQ2_M.gguf` 689.2 MB, `Hy-MT2-1.8B-Q3_K_S.gguf` 831.5 MB).
- Upstream `ggml-org/llama.cpp` supports `hunyuan-dense.cpp` and Android NDK CMake (`examples/llama.android`).

## Product Principles

1. **Zero Friction Over Pages**: Reading flow comes first—one tap on the floating bubble translates the screen, another tap clears cards immediately without blocking touch or interrupting the underlying comic/game.
2. **Bubble Fidelity Over Naive Line-by-Line**: Never treat wrapped OCR lines as separate sentences; reconstruct speech bubbles cleanly and translate with page-level context whenever the engine supports it.
3. **Honest Engine State**: Never conceal when a model is missing, downloading, loading into RAM, or falling back to cloud—surface progress, storage cost, and active fallback clearly.
4. **Day-and-Night Legibility**: Both the control center and floating translation cards must remain crisp, non-glaring, and readable under bright daylight and dark-room reading alike.

## Accessibility & Inclusion

- Minimum 48x48 dp touch targets for all controls.
- Full Material 3 Light and Dark themes plus system font-scale (`sp`) resilience so layouts never clip at 1.3x font scale.
- Edge-to-edge window insets handling so controls never hide under status or navigation bars.
