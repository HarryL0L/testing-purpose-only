#!/bin/sh
# Builds vkprobe directly on the phone, in Termux (test-backend-ops needs the full repo: see build.sh).
#   pkg install clang shaderc vulkan-headers vulkan-loader-android
#   sh build-termux.sh && ./vkprobe --flops
set -e
cd "$(dirname "$0")"
mkdir -p gen
: > gen/shaders.h
echo '#pragma once' >> gen/shaders.h
echo '#include <cstddef>' >> gen/shaders.h
echo '#include <cstdint>' >> gen/shaders.h
for s in shaders/*.comp; do
    n=$(basename "$s" .comp)
    glslc -fshader-stage=compute --target-env=vulkan1.1 -O -mfmt=c "$s" -o "gen/$n.inc"
    printf 'static const uint32_t spv_%s[] =\n#include "%s.inc"\n;\nstatic const size_t spv_%s_size = sizeof(spv_%s);\n' "$n" "$n" "$n" "$n" >> gen/shaders.h
done
clang++ -O2 -std=c++17 -Igen vkprobe.cpp -lvulkan -o vkprobe
echo "built ./vkprobe"
