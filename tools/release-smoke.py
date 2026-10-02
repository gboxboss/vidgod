#!/usr/bin/env python3
"""Smoke test of the *release* APK (minified, as users install it) on a connected emulator.

Drives the app through adb + UI Automator dumps: opens a video with "Open with VidGod", plays,
adds a text, exports. Writes screenshots, a log and a verdict into the output folder.

usage: release-smoke.py <out-dir> <release.apk> <video.mp4>
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

OUT, APK, VIDEO = sys.argv[1], sys.argv[2], sys.argv[3]
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


def find(root, text=None, desc=None, contains=False):
    if root is None:
        return None
    for n in root.iter("node"):
        t, d = n.get("text", ""), n.get("content-desc", "")
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


def wait_for(text=None, desc=None, timeout=30, contains=False):
    end = time.time() + timeout
    while time.time() < end:
        p = find(dump(), text, desc, contains)
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
log(adb("install", "-r", "-g", APK, timeout=300).stdout.decode(errors="replace").strip())
shell("mkdir -p /sdcard/Movies/VidGodSmoke")
adb("push", VIDEO, "/sdcard/Movies/VidGodSmoke/smoke.mp4")
shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/VidGodSmoke/smoke.mp4")
time.sleep(3)
rows = shell("content query --uri content://media/external/video/media --projection _id:_display_name")
log("media rows: " + rows.strip()[-500:])
media_id = None
for line in rows.splitlines():
    if "smoke.mp4" in line:
        m = re.search(r"_id=(\d+)", line)
        if m:
            media_id = m.group(1)
log(f"media id: {media_id}")
shell("logcat -c")


def launch_home():
    shell(f"am start -W -n {PKG}/.MainActivity")
    if not wait_for(text="New project", timeout=30):
        raise RuntimeError("home screen did not appear")


def open_video():
    if not media_id:
        raise RuntimeError("video not in MediaStore")
    shell(f"am start -W -a android.intent.action.VIEW -t video/mp4 -d content://media/external/video/media/{media_id} "
          f"--grant-read-uri-permission -n {PKG}/.MainActivity")
    if not wait_for(text="Export", timeout=45):
        raise RuntimeError("editor did not open")
    time.sleep(3)


def play():
    before = time_text()
    tap(desc="Play")
    time.sleep(2.5)
    tap(desc="Play")
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
step("back_home", lambda: [shell("input keyevent KEYCODE_BACK") for _ in range(3)])

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
