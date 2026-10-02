#!/usr/bin/env python3
"""Smoke test of the *release* APK (minified, as users install it) on a connected emulator.

Drives the app through adb + UI Automator dumps: opens a video with "Open with VidGod", plays,
adds a text, exports. Writes screenshots, a log and a verdict into the output folder.

usage: release-smoke.py <out-dir> <release.apk> <video.mp4> [<speech-video.mp4>]

With a speech video, auto captions (offline speech recognition, which code shrinking could
break) and text to speech are exercised too.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

OUT, APK, VIDEO = sys.argv[1], sys.argv[2], sys.argv[3]
SPEECH = sys.argv[4] if len(sys.argv) > 4 else None
PKG = "com.vidgod.editor"
os.makedirs(OUT, exist_ok=True)
LOG = open(os.path.join(OUT, "smoke.txt"), "w")
failures = []
shot_index = [0]


def log(msg):
    print(msg, flush=True)
    LOG.write(msg + "\n")
    LOG.flush()


def adb(*args, timeout=120):
    try:
        return subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        log(f"adb {' '.join(args)} timed out")
        return subprocess.CompletedProcess(args, 1, b"", b"")


def shell(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout).stdout.decode(errors="replace")


def screenshot(name):
    shot_index[0] += 1
    data = adb("exec-out", "screencap", "-p").stdout
    path = os.path.join(OUT, f"{shot_index[0]:02d}_{name}.png")
    with open(path, "wb") as f:
        f.write(data)


def dump():
    for _ in range(3):
        out = shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
        if "<hierarchy" in out:
            try:
                return ET.fromstring(out[out.index("<?xml") if "<?xml" in out else out.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def find(root, text=None, desc=None, contains=False, ignore_case=False):
    if root is None:
        return None
    for n in root.iter("node"):
        t, d = n.get("text", ""), n.get("content-desc", "")
        if ignore_case:
            t, d = t.lower(), d.lower()
        ok = False
        if text is not None:
            ok = (text in t) if contains else (t == text)
        if desc is not None:
            ok = ok or ((desc in d) if contains else (d == desc))
        if ok:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def wait_for(text=None, desc=None, timeout=30, contains=False, ignore_case=False):
    end = time.time() + timeout
    while time.time() < end:
        p = find(dump(), text, desc, contains, ignore_case)
        if p:
            return p
        time.sleep(1)
    return None


def tap(text=None, desc=None, timeout=15, contains=False):
    p = wait_for(text, desc, timeout, contains)
    if not p:
        raise RuntimeError(f"not found: text={text} desc={desc}")
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(1)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def tap_scrolling(text, swipe, tries=4):
    """Taps [text], scrolling with [swipe] (fractions x1, y1, x2, y2 of the screen) until it shows."""
    w, h = screen_size()
    for _ in range(tries):
        p = find(dump(), text)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1]))
            time.sleep(1)
            return
        x1, y1, x2, y2 = swipe
        adb("shell", "input", "swipe", str(int(w * x1)), str(int(h * y1)), str(int(w * x2)), str(int(h * y2)), "400")
        time.sleep(1)
    raise RuntimeError(f"not found (after scrolling): {text}")


# The editor's bottom toolbar scrolls sideways. Swipe on its icons: lower down, a horizontal swipe is
# the system's gesture for switching apps.
TOOLBAR_LEFT = (0.85, 0.915, 0.25, 0.915)
TOOLBAR_RIGHT = (0.25, 0.915, 0.85, 0.915)
PANEL_UP = (0.5, 0.92, 0.5, 0.62)  # panel content scrolls up


def step(name, fn):
    log(f"--- {name}")
    try:
        fn()
        log(f"PASS {name}")
    except Exception as e:  # noqa: BLE001
        log(f"FAIL {name}: {e}")
        failures.append(f"{name}: {e}")
    screenshot(name)


def app_alive():
    return shell(f"pidof {PKG}").strip() != ""


def time_text():
    root = dump()
    if root is None:
        return ""
    for n in root.iter("node"):
        t = n.get("text", "")
        if re.match(r"^\d\d:\d\d / \d\d:\d\d$", t):
            return t
    return ""


# ---------------------------------------------------------------- setup
def add_video(local, name):
    """Copies a video to the device's gallery and returns its MediaStore id."""
    shell("mkdir -p /sdcard/Movies/VidGodSmoke")
    adb("push", local, f"/sdcard/Movies/VidGodSmoke/{name}")
    shell(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/VidGodSmoke/{name}")
    time.sleep(3)
    rows = shell("content query --uri content://media/external/video/media --projection _id:_display_name")
    found = None
    for line in rows.splitlines():
        if name in line:
            m = re.search(r"_id=(\d+)", line)
            if m:
                found = m.group(1)
    log(f"media id of {name}: {found}")
    return found


log(adb("install", "-r", "-g", APK, timeout=300).stdout.decode(errors="replace").strip())
media_id = add_video(VIDEO, "smoke.mp4")
speech_id = add_video(SPEECH, "speech.mp4") if SPEECH else None
shell("logcat -c")


def launch_home():
    shell(f"am start -W -n {PKG}/.MainActivity")
    if not wait_for(text="New project", timeout=30):
        raise RuntimeError("home screen did not appear")


def open_video(mid=None):
    mid = mid or media_id
    if not mid:
        raise RuntimeError("video not in MediaStore")
    shell(f"am start -W -a android.intent.action.VIEW -t video/mp4 -d content://media/external/video/media/{mid} "
          f"--grant-read-uri-permission -n {PKG}/.MainActivity")
    if not wait_for(text="Export", timeout=45):
        raise RuntimeError("editor did not open")
    time.sleep(3)


def hide_keyboard():
    if "mInputShown=true" in shell("dumpsys input_method"):
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)


def go_home():
    for _ in range(4):
        if find(dump(), text="New project"):
            return
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
    if not wait_for(text="New project", timeout=10):
        raise RuntimeError("did not get back to the home screen")


def reopen_project():
    """The saved project (written by the minified app) opens again with its length."""
    go_home()
    card = wait_for(text="00:04", timeout=10, contains=True)
    if not card:
        raise RuntimeError("project card not on the home screen")
    adb("shell", "input", "tap", str(card[0]), str(card[1]))
    if not wait_for(text="Export", timeout=45):
        raise RuntimeError("project did not reopen")
    time.sleep(3)
    t = time_text()
    log(f"reopened: {t}")
    if not t.endswith("/ 00:04"):
        raise RuntimeError(f"reopened project has the wrong length ({t})")


def captions():
    open_video(speech_id)
    tap_scrolling("Captions", TOOLBAR_LEFT)
    tap_scrolling("Generate captions", PANEL_UP)
    # The first time, the language model (~40 MB) is downloaded. Done when caption items ("CC ...")
    # show in the timeline or the "Added N captions" message appears.
    busy_words = ("Preparing", "Downloading", "Listening", "Recognising")
    idle = 0
    end = time.time() + 420
    while time.time() < end:
        root = dump()
        texts = [n.get("text", "") for n in root.iter("node")] if root is not None else []
        done = [t for t in texts if t.startswith("CC ") or (t.startswith("Added ") and "caption" in t)]
        if done:
            time.sleep(2)
            root = dump()
            log("captions: " + str([n.get("text", "") for n in root.iter("node") if n.get("text", "").startswith("CC ")] if root is not None else done))
            return
        if any("No speech found" in t for t in texts):
            raise RuntimeError("no speech recognised")
        if not app_alive():
            raise RuntimeError("app died while recognising speech")
        busy = any(t.startswith(busy_words) for t in texts)
        idle = 0 if busy else idle + 1
        if idle >= 3:
            raise RuntimeError("captions ended without a result; screen: " + " | ".join(t for t in texts if t)[:400])
        time.sleep(3)
    raise RuntimeError("captions did not finish in 7 minutes")


def text_to_speech():
    tap_scrolling("Audio", TOOLBAR_RIGHT)
    tap(text="Text to speech")
    tap(text="Type what the voice should say")
    shell("input text Hello%sfrom%sVidGod")
    time.sleep(1)
    hide_keyboard()
    tap_scrolling("Add voice", PANEL_UP)
    end = time.time() + 90
    while time.time() < end:
        root = dump()
        texts = [n.get("text", "") for n in root.iter("node")] if root is not None else []
        if any(t.startswith("Voice added") or "Voice: " in t for t in texts):
            return
        if any("failed" in t.lower() for t in texts):
            raise RuntimeError("text to speech failed: " + " | ".join(t for t in texts if "failed" in t.lower()))
        if not app_alive():
            raise RuntimeError("app died while generating the voice")
        time.sleep(2)
    raise RuntimeError("no voice was added")


def play():
    before = time_text()
    p = wait_for(desc="Play", timeout=15)
    if not p:
        raise RuntimeError("no Play button")
    # Pause by tapping the same button again (it turns into Pause). No UI dump in between: dumps
    # wait for the UI to be idle, which takes seconds while the video plays.
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(2)
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(1)
    after = time_text()
    log(f"time {before} -> {after}")
    if before == after:
        raise RuntimeError(f"playhead did not move ({before} -> {after})")


def add_text():
    tap(text="Text")
    tap(text="Add text")
    time.sleep(1)
    shell("input text Release%sbuild")
    time.sleep(1)
    tap(text="Done")
    time.sleep(1)
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)


def export():
    shell("rm -rf /sdcard/Movies/VidGod")
    tap(text="Export")
    tap(text="Export video")
    end = time.time() + 300
    while time.time() < end:
        files = shell("ls /sdcard/Movies/VidGod 2>/dev/null").strip()
        if files.endswith(".mp4"):
            log("exported: " + files)
            time.sleep(3)
            return
        if not app_alive():
            raise RuntimeError("app died during export")
        if "Export failed" in shell("logcat -d -s VidGod:* Diagnostics:* | tail -5"):
            raise RuntimeError("export failed (see logcat)")
        time.sleep(5)
    raise RuntimeError("export did not finish in 5 minutes")


step("home", launch_home)
step("open_video", open_video)
step("play", play)
step("add_text", add_text)
step("play_after_text", play)
step("export", export)
step("reopen_project", reopen_project)
if SPEECH:
    step("auto_captions", captions)
    step("text_to_speech", text_to_speech)
step("back_home", go_home)

crash = shell("logcat -d -b crash")
with open(os.path.join(OUT, "logcat_crash.txt"), "w") as f:
    f.write(crash)
with open(os.path.join(OUT, "logcat.txt"), "w") as f:
    f.write(shell("logcat -d -v threadtime", timeout=180))
if "FATAL EXCEPTION" in crash:
    failures.append("crash: " + crash[crash.index("FATAL EXCEPTION"):][:1500])
log(f"\nRELEASE SMOKE: {'PASS' if not failures else 'FAIL'}")
for f in failures:
    log(" - " + f)
