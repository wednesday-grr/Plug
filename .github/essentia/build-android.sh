#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?}" "${NDK_VERSION:?}" "${ANDROID_API:?}" "${ABI:?}" "${TARGET:?}" "${ESSENTIA_COMMIT:?}"
root="$GITHUB_WORKSPACE"
source_dir="$root/essentia-source"
output="$root/essentia-artifacts/$ABI"
ndk="$ANDROID_HOME/ndk/$NDK_VERSION"
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
wrappers="$RUNNER_TEMP/essentia-compiler"
mkdir -p "$output" "$wrappers"

# Upstream discovers programs named clang/clang++; these wrappers select the NDK target.
for name in clang clang++; do
  cat > "$wrappers/$name" <<EOF
#!/usr/bin/env bash
exec "$tools/$TARGET$ANDROID_API-$name" "\$@"
EOF
  chmod +x "$wrappers/$name"
done
export PATH="$wrappers:$tools:$PATH"
export AR="$tools/llvm-ar"
export RANLIB="$tools/llvm-ranlib"

cd "$source_dir"
test "$(git rev-parse HEAD)" = "$ESSENTIA_COMMIT"

# Upstream Android configuration overrides --std with C++11; retain its C++14 default.
python - <<'PY'
from pathlib import Path
path = Path("wscript")
content = path.read_text()
old = "        ctx.env.CXXFLAGS += ['-std=c++11']\n"
if content.count(old) != 1:
    raise SystemExit("Unexpected upstream Android C++ standard configuration")
path.write_text(content.replace(old, "", 1))
PY

python waf configure --cross-compile-android --lightweight= --fft=KISS \
  --build-static --std=c++14 --ignore-algos=LPC --prefix="$output"
python waf build -j2
python waf install
test -s "$output/lib/libessentia.a"
test -f "$output/include/essentia/algorithmfactory.h"
test -f "$source_dir/src/algorithms/rhythm/rhythmextractor2013.cpp"
grep -q 'RhythmExtractor2013' "$source_dir/src/algorithms/essentia_algorithms_reg.cpp"

mkdir -p "$output/licenses" "$output/include/eigen3"
cp -a /usr/include/eigen3/. "$output/include/eigen3/"
cp "$source_dir/COPYING.txt" "$output/licenses/essentia-COPYING.txt"
cp /usr/share/doc/libeigen3-dev/copyright "$output/licenses/eigen-copyright"
printf 'Essentia commit: %s\nNDK: %s\nAPI: %s\nABI: %s\nEigen package: %s\n' \
  "$ESSENTIA_COMMIT" "$NDK_VERSION" "$ANDROID_API" "$ABI" \
  "$(dpkg-query -W -f='${Version}' libeigen3-dev)" > "$output/build-info.txt"
