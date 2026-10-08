#!/usr/bin/env bash
# Cross-compile bmoe-cli for Android arm64 and stage it into the example app's jniLibs.
# Bash port of build-android.ps1; the cmake flags mirror the "Cross-compile bmoe-cli (arm64)" step
# in .github/workflows/ci.yml, so a local APK carries the same engine CI validates.
#
# The CLI ships as a lib*.so and is launched by the app via ProcessBuilder (no JNI): Android only
# lets an app execute binaries from its nativeLibraryDir, and only files named lib*.so are
# extracted there, hence the rename.
#
# Env overrides: ANDROID_NDK_HOME (default: newest dir under $ANDROID_SDK_ROOT/ndk or
# ~/Android/Sdk/ndk), BUILD_DIR (build-android), ABI (arm64-v8a), API_LEVEL (29),
# BUILD_TYPE (Release), JOBS (nproc).
#
# CPU target is armv8.2-a + dotprod + fp16, deliberately NOT i8mm: a build that pins i8mm (armv8.6)
# SIGILLs in the prefill GEMM on older SoCs. See build-android.ps1 for the full reasoning.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-build-android}"
ABI="${ABI:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-29}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

find_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        echo "$ANDROID_NDK_HOME"
        return
    fi
    local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
    if [ -d "$sdk/ndk" ]; then
        local latest
        latest="$(ls -1 "$sdk/ndk" | sort -V | tail -n 1)"
        if [ -n "$latest" ]; then
            echo "$sdk/ndk/$latest"
            return
        fi
    fi
    echo "Android NDK not found. Set ANDROID_NDK_HOME." >&2
    exit 1
}

NDK="$(find_ndk)"
echo "Using NDK: $NDK"

# Ninja is optional; fall back to cmake's default generator when it is absent.
GENERATOR=()
if command -v ninja >/dev/null 2>&1; then
    GENERATOR=(-G Ninja)
fi

cd "$ROOT"
if [ ! -f third_party/llama.cpp/CMakeLists.txt ]; then
    echo "llama.cpp submodule missing — run: git submodule update --init --recursive" >&2
    exit 1
fi

cmake -S . -B "$BUILD_DIR" "${GENERATOR[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DBMOE_BUILD_TESTS=OFF \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_OPENCL=OFF \
    -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+fp16" \
    -DLLAMA_CURL=OFF
cmake --build "$BUILD_DIR" -j "$JOBS"

# jniLibs is wiped first and filled from an explicit set of names: a recursive sweep once staged a
# stray libggml-opencl.so into published APKs. A library added by a submodule bump then fails at
# dlopen — loud — instead of the APK silently growing a binary nobody chose to ship.
JNI="examples/android/app/src/main/jniLibs/$ABI"
mkdir -p "$JNI"
find "$JNI" -maxdepth 1 -name '*.so' -delete

cp "$BUILD_DIR/cli/bmoe-cli" "$JNI/libbmoe-cli.so"
for name in libggml.so libggml-base.so libggml-cpu.so libllama.so libllama-common.so; do
    src="$(find "$BUILD_DIR" -name "$name" | head -n 1)"
    if [ -z "$src" ]; then
        echo "$name not found in $BUILD_DIR" >&2
        exit 1
    fi
    cp "$src" "$JNI/$name"
done

# bmoe-cli links the c++_shared STL; its runtime lives in the NDK sysroot, not the build tree.
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"

echo "Staged binaries into $JNI"
ls -l "$JNI"
