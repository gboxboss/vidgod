#!/usr/bin/env bash
# Dumps all shaders via a unit test and validates them as GLSL ES 1.00 with glslangValidator.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q :app:testDebugUnitTest --tests 'com.vidgod.editor.ShaderDumpTest' >/dev/null
fail=0
for f in app/build/shaders/*; do
  if ! out=$(glslangValidator "$f" 2>&1); then
    echo "FAIL $f"; echo "$out" | grep -v "^$f$" | head -20; fail=1
  fi
done
[ $fail -eq 0 ] && echo "All $(ls app/build/shaders | wc -l) shaders OK"
exit $fail
