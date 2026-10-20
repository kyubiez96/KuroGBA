#!/usr/bin/env bash
# Builds the two native libraries that ship inside the APK, per ABI:
#   1. libmgba_libretro.so — the mGBA libretro core, from third_party/mgba
#      (CMake, BUILD_LIBRETRO=ON). The upstream target emits
#      "mgba_libretro.so" (empty prefix); it is renamed on staging because
#      Android only extracts jniLibs entries named lib*.so.
#   2. libkurogba.so — the KuroGBA libretro host (native/), which dlopen()s
#      the core at runtime.
# Results are staged into app/src/main/jniLibs/<abi>/ for Gradle to package.
#
# Requires: ANDROID_NDK_HOME (or ANDROID_HOME with ndk/26.3.11579264),
#           CMAKE_BIN (defaults to `cmake` on PATH).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/26.3.11579264}"
CMAKE="${CMAKE_BIN:-cmake}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$ROOT/build-native"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
JOBS="$(nproc 2>/dev/null || echo 4)"

if [ ! -f "$TOOLCHAIN" ]; then
    echo "error: Android NDK toolchain not found at $TOOLCHAIN" >&2
    echo "       set ANDROID_NDK_HOME to an NDK r26 installation" >&2
    exit 1
fi
if [ ! -f "$ROOT/third_party/mgba/CMakeLists.txt" ]; then
    echo "error: mGBA submodule missing — run: git submodule update --init" >&2
    exit 1
fi

CORE_FLAGS=(
    -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON
    -DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_HEADLESS=OFF -DBUILD_TEST=OFF
    -DBUILD_SUITE=OFF -DBUILD_CINEMA=OFF -DBUILD_EXAMPLE=OFF -DBUILD_PYTHON=OFF
    -DBUILD_STATIC=OFF -DBUILD_SHARED=OFF
    -DENABLE_DEBUGGERS=OFF -DENABLE_SCRIPTING=OFF
    -DUSE_PNG=OFF -DUSE_LIBZIP=OFF -DUSE_MINIZIP=OFF -DUSE_SQLITE3=OFF
    -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_LZMA=OFF -DUSE_JSON_C=OFF
    -DUSE_FREETYPE=OFF -DM_CORE_GB=OFF
)

for ABI in $ABIS; do
    echo "=== [$ABI] mGBA libretro core ==="
    "$CMAKE" -S "$ROOT/third_party/mgba" -B "$WORK/core-$ABI" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release "${CORE_FLAGS[@]}"
    "$CMAKE" --build "$WORK/core-$ABI" -j"$JOBS" --target mgba_libretro

    echo "=== [$ABI] KuroGBA host ==="
    "$CMAKE" -S "$ROOT/native" -B "$WORK/host-$ABI" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release
    "$CMAKE" --build "$WORK/host-$ABI" -j"$JOBS"

    mkdir -p "$OUT/$ABI"
    CORE_SO="$(find "$WORK/core-$ABI" -name 'mgba_libretro.so' | head -n 1)"
    if [ -z "$CORE_SO" ]; then
        echo "error: mgba_libretro.so not produced for $ABI" >&2
        exit 1
    fi
    cp "$CORE_SO" "$OUT/$ABI/libmgba_libretro.so"
    cp "$WORK/host-$ABI/libkurogba.so" "$OUT/$ABI/libkurogba.so"
    ls -la "$OUT/$ABI"
done

echo "Native libraries staged in $OUT"
