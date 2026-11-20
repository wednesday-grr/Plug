#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?}" "${NDK_VERSION:?}" "${ANDROID_API:?}" "${ABI:?}" "${TARGET:?}" "${EXPECTED_MACHINE:?}"
output="$GITHUB_WORKSPACE/essentia-artifacts/$ABI"
tools="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
archive="$output/lib/libessentia.a"
work="$RUNNER_TEMP/essentia-verify"
mkdir -p "$work"

# llvm-readelf supports archives and checks every object, not just the archive filename.
"$tools/llvm-readelf" -h "$archive" > "$work/headers.txt"
awk -F: '/Machine:/ { sub(/^[[:space:]]*/, "", $2); print $2 }' \
  "$work/headers.txt" > "$work/machines.txt"
test -s "$work/machines.txt"
if grep -Fxv "$EXPECTED_MACHINE" "$work/machines.txt"; then
  echo "Archive contains an object for the wrong architecture" >&2
  exit 1
fi

cat > "$work/probe.cpp" <<'EOF'
#include <essentia/algorithmfactory.h>
#include <essentia/essentia.h>
extern "C" int essentia_rhythm_link_probe() {
    essentia::init();
    auto* algorithm = essentia::standard::AlgorithmFactory::instance().create("RhythmExtractor2013");
    delete algorithm;
    essentia::shutdown();
    return 0;
}
EOF
"$tools/$TARGET$ANDROID_API-clang++" -std=c++14 -shared -fPIC \
  -Wl,--no-undefined -I"$output/include" -I"$output/include/essentia" \
  -I"$output/include/eigen3" "$work/probe.cpp" "$archive" -latomic -llog \
  -o "$work/libessentia-link-probe.so"
"$tools/llvm-readelf" -h "$work/libessentia-link-probe.so" | grep -F "$EXPECTED_MACHINE"
