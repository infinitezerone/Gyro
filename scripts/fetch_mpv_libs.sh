#!/usr/bin/env bash
#
# Fetches the mpv native libraries into app/src/main/jniLibs.
#
# The .so files are deliberately not in the repository (see .gitignore: "*.so"), but the build
# needs them: libmpv is loaded with dlopen at runtime rather than through the prebuilt AAR, because
# that AAR ships a 138 KB libavfilter without the audio filters the equaliser needs.
#
# Source: mpv-android's official release, which is built against mpv v0.41.0 - the same version the
# Rust side's dlsym table targets. Bumping MPV_RELEASE requires re-checking that table.

set -euo pipefail

MPV_RELEASE="${MPV_RELEASE:-2026-09-17}"
ABIS=(arm64-v8a x86_64)
LIBS=(
  libmpv.so
  libavcodec.so
  libavdevice.so
  libavfilter.so
  libavformat.so
  libavutil.so
  libswresample.so
  libswscale.so
  libc++_shared.so
)

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$repo_root/app/src/main/jniLibs"
base="https://github.com/mpv-android/mpv-android/releases/download/$MPV_RELEASE"

for abi in "${ABIS[@]}"; do
  target="$dest/$abi"
  if [ -f "$target/libavfilter.so" ] && [ "$(wc -c <"$target/libavfilter.so")" -gt 1000000 ]; then
    echo "== $abi already present, skipping"
    continue
  fi

  apk="$(mktemp -t "mpv-$abi-XXXXXX.apk")"
  trap 'rm -f "$apk"' EXIT
  echo "== $abi: downloading $MPV_RELEASE"
  curl -fL --retry 3 --retry-delay 2 -m 600 -o "$apk" \
    "$base/app-default-$abi-release.apk"

  mkdir -p "$target"
  for lib in "${LIBS[@]}"; do
    unzip -o -j "$apk" "lib/$abi/$lib" -d "$target" >/dev/null
  done

  # The AAR it replaces had a 138 KB libavfilter; anything that small again means the wrong build.
  filter_size="$(wc -c <"$target/libavfilter.so")"
  if [ "$filter_size" -lt 1000000 ]; then
    echo "!! libavfilter.so is only $filter_size bytes - this build lacks the audio filters" >&2
    exit 1
  fi
  echo "   ok ($(du -sh "$target" | cut -f1))"
done

echo "Done. libmpv $MPV_RELEASE is in app/src/main/jniLibs"
