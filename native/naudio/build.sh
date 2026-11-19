#!/bin/bash
# Build the audio HAL APEX's libtinyalsav2.so (tinyalsa.cpp) against an
# Android 17 image's libc, as native/gfx/build.sh does:
#
#   build.sh OUT ROOT CRT
#
#   OUT   where libtinyalsav2.so goes
#   ROOT  the image's files (ROOT/apex/com.android.runtime/lib64/bionic)
#   CRT   bionic's crtbegin_so.o and crtend_so.o
#
# tinyalsa's headers come from AOSP at $TAG; clang is $CLANG. naudio itself
# (naudio.c) is the host side: the app runs it in its own process, and in
# Termux it is "clang -O2 -o naudio naudio.c -ldl -lpthread" (nstart.py starts
# A17_DIR/naudio when it is there).
set -euo pipefail
OUT=$(realpath -m "$1"); ROOT=$(realpath "$2"); CRT=$(realpath "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
TAG=${TAG:-android-17.0.0_r1}
CLANG=${CLANG:-clang++-20}
W=${NAUDIO_WORK:-$OUT/work}
mkdir -p "$OUT" "$W/inc/tinyalsa"
if [ ! -e "$W/inc/tinyalsa/.done" ]; then
  curl -sSfL --retry 3 \
    "https://android.googlesource.com/platform/external/tinyalsa_new/+archive/refs/tags/$TAG/include/tinyalsa.tar.gz" |
    tar xz -C "$W/inc/tinyalsa"
  touch "$W/inc/tinyalsa/.done"
fi
$CLANG --target=aarch64-linux-android35 -std=gnu++20 -fPIC -O2 -fno-exceptions -fno-rtti \
  -fvisibility=hidden -I"$W/inc" -c "$HERE/tinyalsa.cpp" -o "$W/tinyalsa.o"
$CLANG --target=aarch64-linux-android35 -fuse-ld=lld -nostdlib -Wl,-z,max-page-size=16384 \
  -shared -Wl,-soname,libtinyalsav2.so -Wl,--no-undefined \
  "$CRT/crtbegin_so.o" "$W/tinyalsa.o" "$CRT/crtend_so.o" -o "$OUT/libtinyalsav2.so" \
  -L"$ROOT/apex/com.android.runtime/lib64/bionic" -lc
ls -la "$OUT/libtinyalsav2.so"
