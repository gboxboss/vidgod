#!/usr/bin/env python3
"""Prints the newest stable versions of the libraries this project depends on.

Run on CI (where Google Maven is reachable). Useful when bumping versions.
"""
import re
import sys
import urllib.request

GOOGLE = "https://dl.google.com/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"

ARTIFACTS = [
    (GOOGLE, "com.android.tools.build:gradle"),
    (GOOGLE, "androidx.media3:media3-transformer"),
    (GOOGLE, "androidx.compose:compose-bom"),
    (GOOGLE, "androidx.activity:activity-compose"),
    (GOOGLE, "androidx.navigation:navigation-compose"),
    (GOOGLE, "androidx.lifecycle:lifecycle-runtime-compose"),
    (GOOGLE, "androidx.core:core-ktx"),
    (GOOGLE, "androidx.datastore:datastore-preferences"),
    (GOOGLE, "androidx.documentfile:documentfile"),
    (GOOGLE, "androidx.exifinterface:exifinterface"),
    (GOOGLE, "androidx.graphics:graphics-shapes"),
    (GOOGLE, "com.google.mlkit:segmentation-selfie"),
    (GOOGLE, "com.google.android.gms:play-services-mlkit-subject-segmentation"),
    (GOOGLE, "com.google.android.material:material"),
    (GOOGLE, "androidx.compose.material3:material3"),
    (GOOGLE, "androidx.compose.material:material-icons-extended"),
    (GOOGLE, "androidx.core:core-splashscreen"),
    (GOOGLE, "androidx.profileinstaller:profileinstaller"),
    (CENTRAL, "org.jetbrains.kotlin:kotlin-gradle-plugin"),
    (CENTRAL, "org.jetbrains.kotlinx:kotlinx-serialization-json"),
    (CENTRAL, "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
    (CENTRAL, "io.coil-kt.coil3:coil-compose"),
    (CENTRAL, "io.coil-kt.coil3:coil-video"),
    (CENTRAL, "com.alphacephei:vosk-android"),
]

STABLE = re.compile(r"^\d+(\.\d+)*$")


def versions(repo, coord):
    group, name = coord.split(":")
    url = f"{repo}/{group.replace('.', '/')}/{name}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            xml = r.read().decode()
    except Exception as e:  # noqa: BLE001
        return f"ERROR {e}"
    vs = re.findall(r"<version>([^<]+)</version>", xml)
    stable = [v for v in vs if STABLE.match(v)]
    return f"stable={stable[-6:]} all_tail={vs[-4:]}"


for repo, coord in ARTIFACTS:
    print(f"{coord}: {versions(repo, coord)}", flush=True)
sys.exit(0)
