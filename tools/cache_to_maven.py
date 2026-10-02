#!/usr/bin/env python3
"""Converts a Gradle `files-2.1` cache into a Maven repository layout.

Only groups hosted on Google's Maven repository are copied (everything else is
available from Maven Central). Usage: cache_to_maven.py <files-2.1 dir> <out dir>
"""
import os
import shutil
import sys

GOOGLE_PREFIXES = (
    "androidx.", "android.arch.", "com.android.", "com.google.android.",
    "com.google.mlkit", "com.google.testing.platform", "com.google.firebase",
    "com.google.gms", "com.google.ar", "com.google.prefab", "com.google.ai.edge",
    "com.google.jetpackcamera", "org.chromium.net", "com.google.assistant",
    "com.google.devtools.ksp",
)
MAX_FILE = 95 * 1024 * 1024  # GitHub rejects files over 100 MB.

src, dst = sys.argv[1], sys.argv[2]
copied = skipped = 0
for group in sorted(os.listdir(src)):
    if not (group.startswith(GOOGLE_PREFIXES) or group in ("com.android", "androidx")):
        continue
    gdir = os.path.join(src, group)
    for module in os.listdir(gdir):
        for version in os.listdir(os.path.join(gdir, module)):
            vdir = os.path.join(gdir, module, version)
            out = os.path.join(dst, *group.split("."), module, version)
            for sha in os.listdir(vdir):
                for name in os.listdir(os.path.join(vdir, sha)):
                    path = os.path.join(vdir, sha, name)
                    if os.path.getsize(path) > MAX_FILE:
                        print("too large, skipped:", path)
                        skipped += 1
                        continue
                    os.makedirs(out, exist_ok=True)
                    shutil.copy2(path, os.path.join(out, name))
                    copied += 1
print(f"copied {copied} files, skipped {skipped}")
