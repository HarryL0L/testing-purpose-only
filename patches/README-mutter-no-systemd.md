# mutter 51.0 without systemd (Termux)

`mutter-51.0-no-systemd.patch` makes libsystemd/libelogind optional.

Apply from the extracted source tree:

    cd mutter-51.0
    patch -p1 < ../mutter-51.0-no-systemd.patch
    meson setup build -Dlogind=disabled ...

## What changes

- New meson option `logind` (feature, default `auto`). When disabled or
  not found, `HAVE_LOGIND` is unset and nothing includes `<systemd/sd-login.h>`.
- `src/backends/meta-launcher.c`: the sd-login session lookups are compiled
  out. The XDG_SESSION_ID and GetSessionByPID lookups still go over D-Bus,
  so a running logind service would still be found. Termux has none, so
  `meta_launcher_new()` fails, which is expected.
- `src/core/meta-context-main.c`: without a systemd user unit, Xwayland is
  started at launch (same as upstream when not in a unit). Use `--no-x11`
  to avoid it.
- `src/core/window.c`: `meta_window_get_unit_cgroup()` returns NULL
  (no per-app cgroup tracking).

## Running

Only headless mode works without logind (it never creates a launcher):

    mutter --headless --virtual-monitor 1280x720 --no-x11 --wayland-display wayland-1

Running on a real screen (DRM/KMS) still needs logind to hand out devices,
and Android blocks DRM access without root anyway.

## Still required (not addressed by this patch)

libudev, gudev-1.0, udev.pc (pass `-Dudev_dir=`), gbm, libinput, libdrm,
and a working EGL + GLES2 (e.g. Mesa surfaceless/llvmpipe).
