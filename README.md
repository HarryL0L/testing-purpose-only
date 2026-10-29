# KDE Plasma 6.8 in GitHub Codespaces

This repository runs a full **KDE neon** virtual machine (Plasma 6.8.1) inside
a GitHub Codespace. It follows the same pattern as the "Windows in Codespaces"
repos: the Codespace boots on GitHub's standard image, and the VM is a separate
Docker container built from the [qemus/qemu](https://github.com/qemus/qemu)
image, with its screen streamed to your browser on port 8006.

Plasma 6.8 is Wayland-only, so it needs a real kernel, systemd and a virtual
GPU. Running it in a VM gives it all three.

## Start

1. Create a Codespace on this branch. A **4-core** machine is recommended.
2. The VM starts automatically when the Codespace is ready. To start it by
   hand, or after stopping it:

   ```bash
   ./start.sh
   ```

3. Watch the VM come up (the first boot downloads the 3.4 GB neon ISO):

   ```bash
   docker logs -f plasma
   ```

4. Open forwarded port **8006** from the **Ports** tab. That page shows the
   VM's screen with the neon live session. You can use the live session, or
   run the installer to install neon onto the 20 GB virtual disk.

## Stop / start

```bash
docker compose stop
docker compose start
```

The disk image and cached ISO live in `./storage/` (ignored by Git) and
persist across Codespace restarts.

## KVM and speed

`start.sh` checks for `/dev/kvm`. When the Codespace host exposes it, the VM
runs with hardware acceleration and is reasonably fast. When it does not,
which is common, the VM runs in QEMU software emulation, roughly ten times
slower: expect 10 to 20 minutes for the live session to reach the desktop,
and a sluggish desktop once there. The script prints which mode it picked.

Whether KVM is available seems to depend on the region and machine pool.
If you get software emulation, try creating the Codespace in another region.

## Configuration

Edit `compose.yml`:

| Variable    | Default                        | Meaning                              |
|-------------|--------------------------------|--------------------------------------|
| `BOOT`      | neon User Edition 20261025 ISO | ISO or disk image URL to boot        |
| `RAM_SIZE`  | `4G`                           | RAM given to the VM                  |
| `CPU_CORES` | `2`                            | CPU cores given to the VM            |
| `DISK_SIZE` | `20G`                          | Size of the virtual disk             |
| `NETWORK`   | `user`                         | User-mode networking (works in Codespaces) |

To track the newest neon build instead of the pinned one, set `BOOT` to
`https://files.kde.org/neon/images/desk/user/current/neon-user-desk-current.iso`.
Any other Plasma 6.8 ISO works too, for example Arch Linux or openSUSE
Tumbleweed.

## Notes

- Rendering inside the guest is software-based (no GPU is passed through), so
  desktop effects will not be smooth.
- This is intended for development and testing. Keep usage within the
  [GitHub Codespaces acceptable use](https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features#codespaces).
