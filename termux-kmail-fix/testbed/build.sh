#!/bin/bash
# build.sh <name> [patch files...]  -> /opt/kw-<name>
set -e
name="$1"; shift
src=/root/src/$name; mkdir -p "$src"
tar -xf /root/src/kwallet-6.32.0.tar.xz -C "$src" --strip-components=1
cd "$src"
for p in "$@"; do echo "applying $(basename $p)"; patch -p1 < "$p"; done
# the chroot has KDE Frameworks 6.30; kwallet 6.32 only asks for 6.32 by convention
sed -i -e 's/find_package(ECM 6\.32\.0/find_package(ECM 6.30.0/' -e 's/set(KF_DEP_VERSION "6\.32\.0")/set(KF_DEP_VERSION "6.30.0")/' CMakeLists.txt
grep -n -E 'find_package\(ECM|KF_DEP_VERSION "' CMakeLists.txt | head -3
cmake -S . -B /root/build-$name -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_INSTALL_PREFIX=/opt/kw-$name \
      -DBUILD_TESTING=OFF -DCMAKE_DISABLE_FIND_PACKAGE_Gpgmepp=ON -DCMAKE_DISABLE_FIND_PACKAGE_KF6DocTools=ON \
      -DKDE_INSTALL_USE_QT_SYS_PATHS=OFF > /root/cmake-$name.log 2>&1 || { tail -30 /root/cmake-$name.log; exit 1; }
ninja -C /root/build-$name > /root/ninja-$name.log 2>&1 || { grep -E 'error|Error' -A6 /root/ninja-$name.log | head -60; exit 1; }
ninja -C /root/build-$name install > /root/install-$name.log 2>&1
ls /opt/kw-$name/bin
