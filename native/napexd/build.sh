#!/bin/sh
# napexd.jar: javac against stubs/ (compile time only), d8 the nmode classes.
#   ./build.sh R8_JAR OUT_JAR
set -e
here=$(cd "$(dirname "$0")" && pwd)
r8=$1; out=$2
tmp=$(mktemp -d)
javac -source 11 -target 11 -nowarn -d "$tmp/classes" -sourcepath "$here/stubs" "$here"/src/nmode/*.java "$here"/stubs/android/*/*.java
mkdir -p "$tmp/dex"
java -cp "$r8" com.android.tools.r8.D8 --min-api 30 --output "$tmp/dex" "$tmp"/classes/nmode/*.class
(cd "$tmp/dex" && jar cf "$out" classes.dex 2>/dev/null || zip -q "$out" classes.dex)
rm -rf "$tmp"
echo "built $out"
