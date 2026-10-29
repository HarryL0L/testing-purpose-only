# KDE Plasma 6.8 in GitHub Codespaces

This repository contains a devcontainer that boots a full **KDE neon** virtual
machine (Plasma 6.8.1) inside a GitHub Codespace. It uses the same approach as
the "Windows in Codespaces" projects: the [qemus/qemu](https://github.com/qemus/qemu)
image runs QEMU with KVM acceleration and streams the display to your browser.

Plasma 6.8 is Wayland-only, so it needs a real kernel, systemd and a virtual
GPU. Running it in a VM gives it all three without any container hacks.

## Usage

1. Open this repository in a Codespace. Pick a **4-core (16 GB)** machine or
   larger. The 2-core machine works but is slow.
2. Wait for the container to start. On first boot it downloads the KDE neon
   ISO (about 3.4 GB), so give it a few minutes.
3. Port **8006** is forwarded automatically and opens in a browser tab. That
   tab shows the VM's screen with the neon live session.
4. Either use the live session directly, or run the installer to install neon
   onto the 24 GB virtual disk. The disk lives in `.devcontainer/storage/`
   and persists across Codespace restarts (it is ignored by git).

## Configuration

Edit `.devcontainer/codespaces.yml`:

| Variable    | Default                        | Meaning                              |
|-------------|--------------------------------|--------------------------------------|
| `BOOT`      | neon User Edition 20261025 ISO | ISO or disk image URL to boot        |
| `RAM_SIZE`  | `half`                         | RAM given to the VM (e.g. `8G`)      |
| `CPU_CORES` | `max`                          | CPU cores given to the VM            |
| `DISK_SIZE` | `24G`                          | Size of the virtual disk             |

To track the newest neon build instead of the pinned one, set `BOOT` to
`https://files.kde.org/neon/images/desk/user/current/neon-user-desk-current.iso`.

Any other Plasma 6.8 ISO works too, for example an Arch Linux or openSUSE
Tumbleweed image.

## Checking KVM

If the viewer shows a very slow boot, confirm that the Codespace exposes KVM:

```sh
ls -l /dev/kvm
```

If the device is missing, QEMU falls back to software emulation. Try a
different machine type or region.

## Notes

- Rendering inside the guest is software-based (no GPU is passed through), so
  desktop effects will not be smooth.
- `initializeCommand` prunes unused Docker images on the Codespace host to
  free disk space before the VM image is pulled.
- This is intended for development and testing. Keep usage within the
  [GitHub Codespaces acceptable use](https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features#codespaces).
