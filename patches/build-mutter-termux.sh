#!/data/data/com.termux/files/usr/bin/bash
# Build mutter 51.0 on Termux without systemd. Verified in termux/termux-docker:x86_64.
# Usage: build-mutter-termux.sh /path/to/mutter-51.0.tar.xz
set -euo pipefail
TARBALL=$(realpath "$1")
PATCH=$(realpath "$(dirname "$0")/mutter-51.0-no-systemd.patch")
D=$HOME/deps            # prefix for deps Termux doesn't package
I=$HOME/mutter-inst     # mutter install prefix
B=$HOME/mutter-build
export PKG_CONFIG_PATH=$D/lib/pkgconfig:$D/share/pkgconfig
export LD_LIBRARY_PATH=$D/lib:$PREFIX/lib
export CC=clang CXX=clang++

apt install -y x11-repo
apt install -y clang ninja pkg-config cmake python git make autoconf automake libtool \
  binutils gettext glib gobject-introspection graphene libpixman gsettings-desktop-schemas \
  libxkbcommon atk libcolord littlecms libei libdisplay-info libdrm pango harfbuzz fribidi \
  libcairo libwayland libwayland-protocols mesa mesa-dev libglvnd-dev libevdev json-glib \
  libx11 libxcomposite libxcursor libxdamage libxext libxfixes libxi xkeyboard-config \
  libxrandr libxinerama libxau libxcb startup-notification gtk4 libadwaita xwayland dbus
pip install meson argcomplete docutils

mkdir -p "$B" && cd "$B"
# Skips any tarball already present in $B
fetch() { [ -s "$1" ] || curl -sSfL -o "$1" "$2"; }
fetch udz.tgz https://github.com/illiliti/libudev-zero/archive/refs/tags/1.0.3.tar.gz
fetch libgudev-238.tar.xz https://download.gnome.org/sources/libgudev/238/libgudev-238.tar.xz
fetch mtdev-1.1.7.tar.bz2 https://bitmath.org/code/mtdev/mtdev-1.1.7.tar.bz2
fetch libinput-1.31.0.tar.gz https://gitlab.freedesktop.org/libinput/libinput/-/archive/1.31.0/libinput-1.31.0.tar.gz
for f in udz.tgz libgudev-238.tar.xz mtdev-1.1.7.tar.bz2 libinput-1.31.0.tar.gz; do tar xf "$f"; done

# libudev (replacement for systemd's libudev)
make -C libudev-zero-1.0.3 PREFIX="$D" install

# libgudev: drop libtool-only flag that clang rejects
sed -i "/'-export-dynamic',/d" libgudev-238/gudev/meson.build
(cd libgudev-238 && meson setup build --prefix="$D" -Dintrospection=disabled -Dvapi=disabled \
  -Dtests=disabled -Dgtk_doc=false && ninja -C build install)

(cd mtdev-1.1.7 && ./configure --prefix="$D" && make -j"$(nproc)" install)

# libinput: Bionic has no index()/rindex()
(cd libinput-1.31.0 && CFLAGS="-Dindex=strchr -Drindex=strrchr" meson setup build --prefix="$D" \
  -Dlibwacom=false -Ddebug-gui=false -Dtests=false -Ddocumentation=false -Dudev-dir="$D/lib/udev" \
  && ninja -C build install)

# mutter only reads udevdir from udev.pc
printf 'udevdir=%s/lib/udev\n\nName: udev\nDescription: udev stub for libudev-zero\nVersion: 251\n' \
  "$D" > "$D/lib/pkgconfig/udev.pc"

rm -rf mutter-51.0 && tar xf "$TARBALL"
cd mutter-51.0 && patch -p1 < "$PATCH"
meson setup build --prefix="$I" -Dlogind=disabled -Dtests=disabled -Dlibwacom=false \
  -Dsound_player=false -Dlibgnome_desktop=false -Dremote_desktop=false -Dintrospection=false \
  -Dprofiler=false -Ddevkit=disabled
ninja -C build install
glib-compile-schemas "$I/share/glib-2.0/schemas"

cat <<MSG
Built. Run headless with:
  export LD_LIBRARY_PATH=$I/lib:$I/lib/mutter-51:$D/lib:\$PREFIX/lib
  export GSETTINGS_SCHEMA_DIR=$I/share/glib-2.0/schemas XDG_RUNTIME_DIR=\$TMPDIR/xdg
  mkdir -p -m700 \$XDG_RUNTIME_DIR
  dbus-run-session -- $I/bin/mutter --headless --virtual-monitor 1280x720 --no-x11 --wayland-display wayland-1
MSG
