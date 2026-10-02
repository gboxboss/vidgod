#!/usr/bin/env python3
"""Turns `am instrument -r` outputs in a results folder into a short Markdown summary."""
import glob
import os
import re
import sys

out = sys.argv[1]
rows = []
for path in sorted(glob.glob(os.path.join(out, "instrument_*.txt"))):
    text = open(path, errors="replace").read()
    test = None
    for block in text.split("INSTRUMENTATION_STATUS_CODE:"):
        m = re.search(r"INSTRUMENTATION_STATUS: test=(\S+)", block)
        if m:
            test = m.group(1)
    codes = re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)
    final = codes[-1] if codes else None
    crashed = "Process crashed" in text or "INSTRUMENTATION_RESULT: shortMsg" in text
    status = {"0": "PASS", "-2": "FAIL", "-1": "ERROR", "-3": "IGNORED", "-4": "SKIPPED"}.get(final, "NO RESULT")
    if crashed:
        status = "CRASH"
    stack = ""
    m = re.search(r"INSTRUMENTATION_STATUS: stack=(.*?)(?:INSTRUMENTATION_STATUS: |INSTRUMENTATION_STATUS_CODE)", text, re.S)
    if m and status != "PASS":
        stack = m.group(1).strip()
    m2 = re.search(r"INSTRUMENTATION_RESULT: (?:shortMsg|longMsg)=(.*)", text)
    if m2 and status != "PASS":
        stack = (stack + "\n" + m2.group(1)).strip()
    name = os.path.basename(path)[len("instrument_"):-4]
    rows.append((name, status, stack))

passed = sum(1 for r in rows if r[1] == "PASS")
print(f"# Device test results: {passed}/{len(rows)} passed\n")
for name, status, _ in rows:
    print(f"- {'✅' if status == 'PASS' else '❌'} `{name}` — {status}")
for name, status, stack in rows:
    if status != "PASS" and stack:
        print(f"\n## {name} ({status})\n```\n" + "\n".join(stack.splitlines()[:40]) + "\n```")
media = os.path.join(out, "media-check.txt")
if os.path.exists(media):
    print("\n## Exported files (ffprobe / loudness)\n```\n" + open(media, errors="replace").read()[-12000:] + "\n```")
smoke = os.path.join(out, "release-smoke", "smoke.txt")
if os.path.exists(smoke):
    print("\n## Release APK smoke test\n```\n" + open(smoke, errors="replace").read()[-6000:] + "\n```")
log = os.path.join(out, "test-out", "log.txt")
if os.path.exists(log):
    print("\n## Test log\n```\n" + open(log, errors="replace").read()[-20000:] + "\n```")
