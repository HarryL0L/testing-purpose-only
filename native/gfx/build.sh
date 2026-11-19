#!/bin/bash
# Build native mode's graphics HALs against an Android 17 image's libraries:
#
#   build.sh OUT ROOT CRT
#
#   OUT   where nalloc, mapper.native.so and ncomposer go
#   ROOT  the image's files: ROOT/system/lib64, ROOT/vendor/lib64 and
#         ROOT/apex/com.android.runtime/lib64/bionic (only linked against)
#   CRT   bionic's crtbegin_dynamic.o, crtend_android.o, crtbegin_so.o, crtend_so.o
#
# Headers come from AOSP at $TAG (gitiles archives), the AIDL NDK headers are
# generated with the SDK's aidl, and clang is $CLANG (LLVM 20 or newer: the
# image's libc++ headers need it). Sources are cached in $GFX_WORK.
set -euo pipefail
OUT=$(realpath -m "$1"); ROOT=$(realpath "$2"); CRT=$(realpath "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
TAG=${TAG:-android-17.0.0_r1}
CLANG=${CLANG:-clang++-20}
W=${GFX_WORK:-$OUT/work}
G=https://android.googlesource.com/platform
mkdir -p "$OUT" "$W"
cd "$W"

get() {  # project dir path
  [ -e "$2/.done" ] && return
  rm -rf "$2" && mkdir -p "$2"
  curl -sSfL --retry 3 "$G/$1/+archive/refs/tags/$TAG/$3.tar.gz" | tar xz -C "$2"
  touch "$2/.done"
}
get bionic bionic_inc libc/include
get bionic bionic_uapi libc/kernel/uapi
get bionic bionic_auapi libc/kernel/android/uapi
get prebuilts/clang/host/linux-x86 cxx clang-r584948b/include/c++/v1
get prebuilts/clang/host/linux-x86 cxx_aarch64 clang-r584948b/android_libc++/platform/aarch64/include
get frameworks/native binder_ndk libs/binder/ndk
get system/core libcutils_inc libcutils/include
get system/logging liblog_inc liblog/include
get hardware/interfaces hi_composer3 graphics/composer/aidl
get hardware/interfaces hi_common graphics/common/aidl
get hardware/interfaces hi_alloc graphics/allocator/aidl
get hardware/interfaces hi_mapper graphics/mapper/stable-c
get hardware/interfaces hi_hcommon common/aidl
get hardware/interfaces hi_drmcommon drm/common/aidl
if [ ! -x bt/android-15/aidl ]; then
  curl -sSfL --retry 3 -o bt.zip https://dl.google.com/android/repository/build-tools_r35_linux.zip
  rm -rf bt && unzip -q bt.zip -d bt && rm bt.zip
fi

# Stand-ins for two headers the mapper helpers include but don't need.
mkdir -p stub/android-base stub/android
: > stub/android-base/unique_fd.h
cat > stub/android/rect.h <<'EOF'
#pragma once
#include <stdint.h>
typedef struct ARect { int32_t left; int32_t top; int32_t right; int32_t bottom; } ARect;
EOF

# AIDL NDK headers (stable interfaces, frozen versions the image ships)
if [ ! -e gen/.done ]; then
  rm -rf gen && mkdir -p gen/h gen/src
  C3=hi_composer3/aidl_api/android.hardware.graphics.composer3/5
  GC=hi_common/aidl_api/android.hardware.graphics.common/7
  HC=hi_hcommon/aidl_api/android.hardware.common/2
  AL=hi_alloc/aidl_api/android.hardware.graphics.allocator/2
  DC=hi_drmcommon/aidl_api/android.hardware.drm.common/1
  for d in "$C3:5" "$GC:7" "$HC:2" "$AL:2" "$DC:1"; do
    dir=${d%:*}; ver=${d##*:}; hash=$(tail -1 "$dir/.hash")
    find "$dir" -name '*.aidl' | while read -r f; do
      bt/android-15/aidl --lang=ndk --structured --stability=vintf --version="$ver" --hash="$hash" \
        -I$C3 -I$GC -I$HC -I$AL -I$DC -o gen/src -h gen/h "$f"
    done
  done
  touch gen/.done
fi

RES=$($CLANG -print-resource-dir)
CXX=($CLANG --target=aarch64-linux-android35 -std=gnu++20 -fPIC -O2 -fno-exceptions -fno-rtti
     -Wall -Wno-deprecated-declarations -nostdinc -nostdinc++
     -isystem "$W/cxx_aarch64/c++/v1" -isystem "$W/cxx" -isystem "$RES/include"
     -isystem "$W/bionic_inc" -isystem "$W/bionic_uapi" -isystem "$W/bionic_uapi/asm-arm64"
     -isystem "$W/bionic_auapi"
     -I"$W/gen/h" -I"$W/binder_ndk/include_cpp" -I"$W/binder_ndk/include_ndk"
     -I"$W/binder_ndk/include_platform" -I"$W/liblog_inc" -I"$W/libcutils_inc" -I"$W/stub"
     -I"$W/hi_mapper/include" -I"$W/hi_mapper/implutils/include")
S=$ROOT/system/lib64; V=$ROOT/vendor/lib64
LINK=($CLANG --target=aarch64-linux-android35 -fuse-ld=lld -nostdlib -Wl,--allow-shlib-undefined
      -Wl,-z,max-page-size=16384 -Wl,--dynamic-linker=/system/bin/linker64
      -L"$ROOT/apex/com.android.runtime/lib64/bionic")
EXE=(-pie "$CRT/crtbegin_dynamic.o")
O=$W/obj && mkdir -p "$O"

# nalloc: a vendor program (vendor libraries + LLNDK)
"${CXX[@]}" -c "$HERE/nalloc.cpp" -o "$O/nalloc.o"
"${LINK[@]}" -L"$V" -L"$S" "${EXE[@]}" "$O/nalloc.o" "$CRT/crtend_android.o" -o "$OUT/nalloc" \
  "$V/android.hardware.graphics.allocator-V2-ndk.so" "$V/android.hardware.common-V2-ndk.so" \
  "$V/android.hardware.graphics.common-V7-ndk.so" -lbinder_ndk -llog "$V/libc++.so" -lc -lm -ldl

# mapper.native.so: loaded into every graphics client (sphal namespace)
"${CXX[@]}" -fvisibility=hidden -c "$HERE/mapper.cpp" -o "$O/mapper.o"
"${LINK[@]}" -L"$V" -L"$S" -shared -Wl,-soname,mapper.native.so -Wl,--no-undefined \
  "$CRT/crtbegin_so.o" "$O/mapper.o" "$CRT/crtend_so.o" -o "$OUT/mapper.native.so" \
  "$V/libcutils.so" -llog "$V/libc++.so" -lc -lm -ldl

# gralloc.native.so: a gralloc0 module, only for Mesa's YUV buffer layouts
"${CXX[@]}" -fvisibility=hidden -c "$HERE/gralloc.cpp" -o "$O/gralloc.o"
"${LINK[@]}" -shared -Wl,-soname,gralloc.native.so -Wl,--no-undefined \
  "$CRT/crtbegin_so.o" "$O/gralloc.o" "$CRT/crtend_so.o" -o "$OUT/gralloc.native.so" -lc

# ncomposer: a system program (the composer3 NDK library is only in /system)
"${CXX[@]}" -c "$HERE/ncomposer.cpp" -o "$O/ncomposer.o"
"${LINK[@]}" -L"$S" "${EXE[@]}" "$O/ncomposer.o" "$CRT/crtend_android.o" -o "$OUT/ncomposer" \
  "$S/android.hardware.graphics.composer3-V5-ndk.so" "$S/android.hardware.common-V2-ndk.so" \
  "$S/android.hardware.graphics.common-V7-ndk.so" "$S/android.hardware.drm.common-V1-ndk.so" \
  -lbinder_ndk -llog "$S/libc++.so" -lc -lm -ldl

ls -la "$OUT/nalloc" "$OUT/mapper.native.so" "$OUT/gralloc.native.so" "$OUT/ncomposer"
