# VidGod — free CapCut-style video editor for Android

Every "pro" feature unlocked, no watermark, exports in the format TikTok likes
(MP4 · H.264 High · AAC · 1080×1920 · 30/60 fps · fast-start).

## Download
Every push to `main` builds signed APKs: open the **Releases** page of this repo and
download `VidGod-v1.0.<n>-arm64.apk` (almost every phone from 2017 on; ~15 MB) or the
`-universal` APK, then open it on your phone (allow "install unknown apps").

## Features
**Editing:** multi-track timeline (main track, picture-in-picture overlays, text, stickers,
effects, filters, audio), pinch-to-zoom timeline, split, trim, delete, duplicate, replace,
reorder, freeze frame, reverse (video + audio), speed 0.1×–100× with pitch lock, speed curves
(Montage, Hero, Bullet, Flash in/out…), undo/redo, auto-save, projects library.

**Visuals:** 40+ filters with intensity, 14 adjustments (brightness, contrast, saturation,
exposure, temperature, tint, highlights, shadows, vibrance, hue, sharpen, vignette, fade,
grain), 50+ video effects (shake, glitch, VHS, old film, snow, sparkle, kaleidoscope, split
screens, fisheye, neon…), 27 transitions (dissolve, push, slide, wipe, zoom, spin, glitch,
heart, circle…), in/out/combo animations, keyframes (position, scale, rotation, opacity,
volume), masks (linear, mirror, circle, rectangle, heart, star) with feather/invert, chroma key
(green screen), background removal (on-device ML Kit), crop, rotate, mirror, opacity,
canvas ratio (9:16, 16:9, 1:1, 4:5, …) with blurred/colour/image backgrounds.

**Text & stickers:** 40 fonts (32 bundled Google Fonts), stroke, background label, shadow,
gradient, spacing, alignment, 18 text templates, typewriter and other text animations,
hundreds of emoji stickers, shape stickers, image stickers.

**Audio:** music from your phone, extract audio from any video, voice-over recording,
text-to-speech, volume up to 200 %, fade in/out, voice effects (chipmunk, deep, robot, echo,
telephone, megaphone…), noise reduction, beat detection.

**AI:** offline auto-captions with word-by-word karaoke highlight (Vosk speech recognition,
17 languages), background removal, text-to-speech.

**Export:** 480p–4K, 24–60 fps, bitrate presets, H.264/HEVC, saves to Movies/VidGod,
one-tap share to TikTok.

## Tech
Kotlin · Jetpack Compose · AndroidX Media3 (CompositionPlayer for real-time preview,
Transformer for hardware-accelerated export) · custom OpenGL ES shaders for every visual
effect · Vosk · ML Kit.

```
app/src/main/java/com/vidgod/editor/
  model/        project data (serialised as JSON)
  engine/       composition builder, preview & export, GL effects, catalogs, audio DSP
  features/     captions, TTS, voice-over, reverse, background removal, beats
  editor/       editor state, undo/redo and edit operations
  ui/           Compose screens (home, editor, timeline, panels, export)
```

## Building
Open in Android Studio (or run `./gradlew assembleRelease`). Requires JDK 17+.
`tools/validate-shaders.sh` checks every GLSL shader with `glslangValidator`.
