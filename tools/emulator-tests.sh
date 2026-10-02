#!/usr/bin/env bash
# Runs the device tests on a connected emulator and collects everything into results/.
# Each test method runs in its own instrumentation so that one crash cannot hide the others.
set -u
OUT=results
mkdir -p "$OUT"
PKG=com.vidgod.editor.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner

adb wait-for-device
{
  echo "release: $(adb shell getprop ro.build.version.release)"
  echo "sdk: $(adb shell getprop ro.build.version.sdk)"
  echo "abi: $(adb shell getprop ro.product.cpu.abi)"
  adb shell dumpsys SurfaceFlinger | grep -i -m3 "GLES" || true
  adb shell dumpsys media.player 2>/dev/null | grep -i -m20 "c2.android" || true
} > "$OUT/device.txt" 2>&1

adb install -r -g app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$OUT/install.txt" 2>&1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

# List the tests without running them.
adb shell am instrument -w -r -e log true -e package com.vidgod.editor "$RUNNER" > "$OUT/test-list.txt" 2>&1
TESTS=$(python3 - "$OUT/test-list.txt" <<'PY'
import re, sys
cls = None
seen = []
for line in open(sys.argv[1], errors="replace"):
    m = re.match(r"INSTRUMENTATION_STATUS: class=(.*)", line.strip())
    if m: cls = m.group(1)
    m = re.match(r"INSTRUMENTATION_STATUS: test=(.*)", line.strip())
    if m and cls:
        t = f"{cls}#{m.group(1)}"
        if t not in seen: seen.append(t)
print("\n".join(seen))
PY
)
echo "$TESTS" > "$OUT/tests.txt"

for t in $TESTS; do
  name=${t##*.}
  echo "=== $t" | tee -a "$OUT/progress.txt"
  start=$(date +%s)
  timeout 900 adb shell am instrument -w -r -e class "$t" "$RUNNER" > "$OUT/instrument_${name//#/_}.txt" 2>&1
  echo "    exit=$? seconds=$(( $(date +%s) - start ))" | tee -a "$OUT/progress.txt"
done

# The release APK (minified, as users install it) gets an adb-driven smoke test.
REL=app/build/outputs/apk/release/app-release.apk
if [ -f "$REL" ]; then
  # A video with speech (CI generates the sample with espeak-ng) for auto captions.
  SPEECH_WAV=app/src/androidTest/assets/media/speech.wav
  SPEECH_MP4=
  if [ -f "$SPEECH_WAV" ] && command -v ffmpeg > /dev/null; then
    SPEECH_MP4=$(mktemp -d)/speech.mp4
    ffmpeg -loglevel error -y -f lavfi -i color=c=0x2a4d8f:s=720x1280:r=30 -i "$SPEECH_WAV" -shortest \
      -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 128k "$SPEECH_MP4" || SPEECH_MP4=
  fi
  timeout 1500 python3 tools/release-smoke.py "$OUT/release-smoke" "$REL" app/src/androidTest/assets/media/portrait.mp4 $SPEECH_MP4 \
    > "$OUT/release-smoke.txt" 2>&1 || echo "release smoke exit=$?" >> "$OUT/release-smoke.txt"
fi

kill $LOGCAT_PID 2>/dev/null
adb pull "/sdcard/Android/data/$PKG/files/test-out" "$OUT/test-out" > /dev/null 2>&1 || true
if command -v ffprobe > /dev/null; then bash tools/check-media.sh "$OUT/test-out" > "$OUT/media-check.txt" 2>&1; fi
adb exec-out run-as $PKG cat files/diagnostics/errors.txt > "$OUT/app_errors.txt" 2>/dev/null || true
adb exec-out run-as $PKG cat files/diagnostics/crash.txt > "$OUT/app_crash.txt" 2>/dev/null || true
grep -E "AndroidRuntime|FATAL|VidGod|TransitionStore|PreviewController|Transformer|CompositionPlayer|ExoPlayer|MediaCodec.*(E|W) |GlUtil|VideoFrameProcessor| E [A-Za-z]" "$OUT/logcat.txt" | grep -v -E "ResourcesCompat|chatty" | tail -4000 > "$OUT/logcat_filtered.txt" || true

python3 tools/summarize-tests.py "$OUT" > "$OUT/SUMMARY.md" || true
cat "$OUT/SUMMARY.md"
exit 0
