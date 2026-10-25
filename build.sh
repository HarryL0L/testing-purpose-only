#!/usr/bin/env bash
# Rebuilds termux-x11 with the onscreen Xbox gamepad patch.
# Needs JDK 17+, Android SDK with NDK 29.0.14206865 (ANDROID_HOME set).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UPSTREAM_COMMIT=7e5f5c5c9226e2cdab392b2efba91bc4bd86463e
WORK="${1:-$HERE/build/termux-x11}"

if [ ! -d "$WORK/.git" ]; then
    git clone https://github.com/termux/termux-x11 "$WORK"
    git -C "$WORK" checkout "$UPSTREAM_COMMIT"
    git -C "$WORK" submodule update --init --recursive --depth 1
fi
git -C "$WORK" apply "$HERE/patches/termux-x11-xbox-gamepad.patch"
(cd "$WORK" && ./gradlew :lorie-app:assembleStandaloneDebug)
cp "$WORK/lorie-app/build/outputs/apk/standalone/debug/termux-x11-universal-debug.apk" "$HERE/apk/termux-x11-xbox-gamepad.apk"
