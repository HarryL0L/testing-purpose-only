#!/bin/sh
# Builds the probe for Android arm64 (needs the Android NDK) and packs gpuprobe.tar.gz
set -e
cd "$(dirname "$0")"
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK:-$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/ndk/* | sort -V | tail -1)}}
cmake -S . -B build-android -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release
cmake --build build-android -j"$(nproc)"
rm -rf out && mkdir -p out/gpuprobe
for b in vkprobe test-backend-ops maligemm-test; do
    cp "build-android/bin/$b" out/gpuprobe/ 2>/dev/null || cp "build-android/$b" out/gpuprobe/
done
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip out/gpuprobe/vkprobe out/gpuprobe/test-backend-ops out/gpuprobe/maligemm-test
cp run.sh README.md out/gpuprobe/
tar -C out -czf out/gpuprobe.tar.gz gpuprobe
echo "built out/gpuprobe.tar.gz"
