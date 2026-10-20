# mutter 51.0 without systemd (Termux)

Status: **builds and runs headless on Termux** (verified in
`termux/termux-docker:x86_64`, clang 21, glib 2.90). A Wayland client
connects and sees `wl_compositor`, `xdg_wm_base`, `wl_seat`, `wl_output`,
`wl_shm` (39 globals). Not yet tested on a real Android device.

- `mutter-51.0-no-systemd.patch` - the source patch
- `build-mutter-termux.sh` - full build recipe (deps + mutter)

    ./build-mutter-termux.sh /path/to/mutter-51.0.tar.xz

## What the patch changes

- New meson option `logind` (feature, default `auto`). When disabled or not
  found, `HAVE_LOGIND` is unset (added to `config.h.meson`) and nothing
  includes `<systemd/sd-login.h>`.
- `src/backends/meta-launcher.c`: sd-login session lookups compiled out. The
  XDG_SESSION_ID and GetSessionByPID lookups still go over D-Bus, so a running
  logind service would still be found; on Termux there is none, which is fine
  because headless mode never creates a launcher.
- `src/core/meta-context-main.c`: without a systemd user unit, Xwayland is
  started at launch (same as upstream when not in a unit). Use `--no-x11`.
- `src/core/window.c`: `meta_window_get_unit_cgroup()` returns NULL.
- `src/wayland/meta-xwayland.c`: falls back to `arc4random_buf()` when
  `getrandom()` is unavailable (Android API < 28, which Termux targets).
- `src/meson.build`: always build `meta-selection-source-remote.c`.
  `meta-clipboard-session.c` (always built) calls it, so upstream fails to
  link with `-Dremote_desktop=false`. Not Termux-specific.
- `src/compositor/plugins/meson.build`: link the default plugin against
  libmutter. Bionic's linker doesn't let `dlopen()`ed modules see symbols
  from the executable's libraries.

## Dependencies Termux doesn't package

Built from source by the script into `~/deps`:

| Dep | Version | Fix needed |
|---|---|---|
| libudev-zero (provides libudev) | 1.0.3 | none |
| libgudev | 238 | drop `-export-dynamic` (clang rejects it) |
| mtdev | 1.1.7 | none |
| libinput | 1.31.0 (mutter needs >= 1.31) | `-Dindex=strchr -Drindex=strrchr` (not in Bionic) |
| udev.pc | stub | mutter only reads `udevdir` from it |

Disabled in the build: remote desktop (Termux pipewire 1.4.9 < required
1.6.0), libwacom, sound player, gnome-desktop, introspection, profiler, tests.

## Running

    dbus-run-session -- mutter --headless --virtual-monitor 1280x720 --no-x11 --wayland-display wayland-1

Renders in software ("surfaceless renderer without GPU"). Warnings about
`org.freedesktop.locale1` and colord are harmless (services absent).

Running on the real screen (DRM/KMS) still needs logind and root-level DRM
access, which Android doesn't give.

## termux-docker notes (not needed on a device)

- termux-exec isn't preloaded (no `getprop`), so `#!/usr/bin/env` and
  `/bin/sh` shebangs fail; symlink `/bin/sh` and `/usr/bin/env` into `$PREFIX`.
- Set `LD_LIBRARY_PATH` to include `$PREFIX/lib` explicitly when overriding it.
