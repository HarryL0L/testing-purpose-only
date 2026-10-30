#!/usr/bin/env bash
# Builds the XInput bridge DLLs. Needs gcc-mingw-w64 (x86_64 and i686).
set -euo pipefail
cd "$(dirname "$0")"
for arch in x86_64 i686; do
    out="$arch"
    mkdir -p "$out"
    "$arch-w64-mingw32-gcc" -O2 -shared -s -static-libgcc -Wall -Wextra -o "$out/xinput1_3.dll" \
        xinput_bridge.c xinput_bridge.def -Wl,--enable-stdcall-fixup -lws2_32
    cp "$out/xinput1_3.dll" "$out/xinput1_4.dll"
    cp "$out/xinput1_3.dll" "$out/xinput9_1_0.dll"
done
