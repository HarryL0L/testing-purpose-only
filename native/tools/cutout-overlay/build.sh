#!/bin/sh
# The display cutout overlay template (an RRO on the framework):
#   ./build.sh AAPT2 ANDROID_JAR OUT_APK
# Unsigned: the app fills it in, then installs it (unsigned is fine: framework overlays apply from /product/overlay at boot).
set -e
here=$(cd "$(dirname "$0")" && pwd); tmp=$(mktemp -d)
"$1" compile --dir "$here/res" -o "$tmp/res.zip"
"$1" link -I "$2" --manifest "$here/AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 29 \
    --no-compress -o "$tmp/unsigned.apk" "$tmp/res.zip"
cp "$tmp/unsigned.apk" "$3"
rm -rf "$tmp"
