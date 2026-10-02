# VidGod — developer guide

This file explains how the project is put together so you (or another AI session) can keep
building on it.

## Build & run
* Android Studio (latest) → *Open* this folder → run the `app` configuration on a phone.
* Command line: `./gradlew assembleDebug` (debug, installs next to release as `com.vidgod.editor.debug`)
  or `./gradlew assembleRelease` (signed with `keystore/vidgod-release.jks`, password `vidgod2026`).
* Every push to `main` runs `.github/workflows/android.yml`, which builds the release APKs and
  publishes them as a GitHub Release (universal / arm64 / arm32).
* Unit tests: `./gradlew testDebugUnitTest`. Shader check: `tools/validate-shaders.sh`
  (needs `glslangValidator`, e.g. `apt install glslang-tools`).
* Versions live in `gradle/libs.versions.toml` (AGP 9.4, Kotlin 2.4, Compose BOM, Media3 1.11).
  compileSdk is 37.1 because the newest AndroidX libraries require it.

## Architecture
```
model/      Project, VisualClip, AudioClip, TextClip, StickerClip, EffectClip, FilterClip…
            (kotlinx.serialization; stored as files/projects/<id>/project.json)
data/       ProjectRepository (JSON storage), MediaProbe (duration/size/rotation), Diagnostics
editor/     EditorViewModel (state, undo/redo, all user actions), ProjectOps (pure edit functions),
            Selection & Panel enums
engine/     CompositionFactory  – Project → Media3 Composition (used by preview AND export)
            PreviewController   – CompositionPlayer wrapper; live property updates via LiveProject
            ExportController    – Transformer export (H.264/HEVC + AAC MP4) + save to gallery
            effects/            – OpenGL effects: ClipCanvasEffect (layout, crop, mask, chroma key,
                                  background, animations, transitions), ColorGradeEffect (filters &
                                  adjustments), FxEffect (video effects), TransitionStore
            catalog/            – Filters, Effects (GLSL), Transitions (GLSL), Animations
            text/               – Fonts, TextRenderer, StickerRenderer, OverlayLanes (text/sticker overlays)
            audio/              – volume envelope, voice effects, noise reduction processors
features/   AutoCaptions (Vosk), TTS & voice recorder, Reverser, BackgroundRemoval (ML Kit),
            BeatDetector, SilenceCutter, SoundEffects (synthesised), AutoStyles
media/      Thumbnails and audio waveform decoding
ui/         Compose UI: home/, editor/ (EditorScreen, Timeline, PreviewPane, ExportOverlay,
            panels/*), common/ (shared widgets), theme/
```

### How rendering works
* The **main track** is one Media3 sequence; each clip gets `[ColorGradeEffect, (BackgroundRemoval),
  ClipCanvasEffect, FxEffect…]`. `ClipCanvasEffect` outputs frames of the canvas size
  (e.g. 1080×1920) with the clip placed on the canvas background.
* **Overlays (picture-in-picture)** are extra sequences placed *before* the main sequence (Media3
  draws the first sequence on top) and padded with gaps; `VideoCompositorSettings` hides a layer
  while it is in a gap.
* **Texts and stickers** are drawn by `OverlayEffect`s added as composition-level effects.
* **Transitions**: clips cannot overlap in a Media3 sequence, so the incoming clip blends with a
  frozen copy of the outgoing clip's last frame (`TransitionStore`).
* GL effects receive *composition time* in `drawFrame`, so per-clip animations use
  `presentationTimeUs - clipStartUs`.
* Property changes that do not change the structure (positions, filters, text…) are applied live:
  effects read the newest project from `LiveProject` and the player redraws the last frame.
  Structural changes (trims, speed, adding clips) rebuild the composition (debounced).

### Adding things
* **Filter**: add a `FilterDef` to `engine/catalog/Filters.kt` (parameters of `GradeParams`).
* **Video effect**: add an `FxDef` to `engine/catalog/Effects.kt` with GLSL `vec4 fx(vec2 uv)`.
* **Transition**: add a `TransitionDef` to `engine/catalog/Transitions.kt` with GLSL `vec4 transition(vec2 uv)`.
* **Animation**: add to `engine/catalog/Animations.kt` (`applyIn`/`applyOut`/`applyCombo`).
* **Font**: drop a `.ttf` into `app/src/main/assets/fonts/` and add a `FontDef` in `engine/text/Fonts.kt`.
* Run `tools/validate-shaders.sh` after changing GLSL — shader errors otherwise only show on a phone.

## Debugging on a phone
Crashes are saved and shown on the next launch with a *Share report* button; the home screen
menu (⋮ → *Report a problem*) shares the recent error log.
