# KDE Plasma 6.8 in GitHub Codespaces

This repository runs a full **KDE neon** virtual machine (Plasma 6.8.1) inside
a GitHub Codespace. It follows the same pattern as the "Windows in Codespaces"
repos: the Codespace boots on GitHub's default image, and the VM is a separate
Docker container built from the [qemus/qemu](https://github.com/qemus/qemu)
image, with its screen streamed to your browser on port 8006.

The devcontainer deliberately names no image. Codespaces then uses its cached
default image and starts in about a minute; naming the image forces a
multi-gigabyte pull on every new Codespace.

Plasma 6.8 is Wayland-only, so it needs a real kernel, systemd and a virtual
GPU. Running it in a VM gives it all three.

## Start

1. Create a Codespace on this branch. A **4-core** machine is recommended.
2. The VM starts automatically when the Codespace is ready. To start it by
   hand, or after stopping it:

   ```bash
   ./start.sh
   ```

   The first run downloads the 3.4 GB neon ISO into `./storage/` before
   starting the VM; the script shows the progress.

3. Watch the VM come up:

   ```bash
   docker logs -f plasma
   ```

4. Open forwarded port **8006** from the **Ports** tab. That page shows the
   VM's screen with the neon live session. You can use the live session, or
   run the installer to install neon onto the 12 GB virtual disk.

## Stop / start

```bash
docker compose stop
docker compose start
```

The disk image and cached ISO live in `./storage/` (ignored by Git) and
persist across Codespace restarts.

## KVM

The VM needs `/dev/kvm`, which Codespaces machines expose. To confirm QEMU is
using it:

```bash
docker logs plasma 2>&1 | grep -i kvm
```

No warning about KVM being unavailable means hardware acceleration is on.

## Configuration

Edit `compose.yml`:

| Variable    | Default | Meaning                                                    |
|-------------|---------|------------------------------------------------------------|
| `RAM_SIZE`  | `4G`    | RAM given to the VM                                        |
| `CPU_CORES` | `2`     | CPU cores given to the VM                                  |
| `DISK_SIZE` | `12G`   | Size of the virtual disk                                   |
| `NETWORK`   | `user`  | User-mode networking; bridge mode has no DNS in Codespaces |

The ISO URL is set at the top of `start.sh`. To track the newest neon build
instead of the pinned one, change it to
`https://files.kde.org/neon/images/desk/user/current/neon-user-desk-current.iso`
and delete `storage/neon.iso`. Any other Plasma 6.8 ISO works too, for
example Arch Linux or openSUSE Tumbleweed.

Docker's bridge network in Codespaces cannot resolve DNS names, so the ISO
is downloaded by `start.sh` on the Codespace and handed to the container as
a file. The qemus image does not support host networking.

## If the VM keeps restarting

Find out why before changing anything:

```bash
docker inspect plasma --format 'exit={{.State.ExitCode}} oom={{.State.OOMKilled}}'
docker logs plasma --tail 40
```

`oom=true` means the host ran out of memory: lower `RAM_SIZE` in
`compose.yml`. An error line in the log names the real problem. An error
about `/dev/kvm` means this machine has no KVM. After editing
`compose.yml`, apply it with:

```bash
docker compose down && ./start.sh
```

## Why a VM and not a container

A container would be faster than an emulated VM, so it was tested with Alpine
edge (Plasma 6.8.1). It cannot work for Plasma 6.8:

- Plasma 6.8 dropped the X11 session, so the old "Xvnc + Plasma on X11" trick
  is gone.
- KWin 6.8 has no software-only compositing path left. Its virtual backend
  refuses to start without a DRM render device (`/dev/dri/renderD*`), and
  the `KWIN_COMPOSE=Q` software renderer was removed.
- Its nested X11 backend requires DRI3 1.2+, which Xvnc and Xvfb do not offer.
- Its nested Wayland backend exits when the host compositor has no DRM device
  either (tested on a headless wlroots compositor with the pixman renderer).

A Codespace container has no `/dev/dri`, so none of these can be satisfied.
A VM gets a virtual GPU from its own kernel, which is why it works.

## Notes

- Rendering inside the guest is software-based (no GPU is passed through), so
  desktop effects will not be smooth.
- This is intended for development and testing. Keep usage within the
  [GitHub Codespaces acceptable use](https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features#codespaces).
