# Android 17 in GitHub Codespaces

This branch runs **Android 17 (API 37)** inside a GitHub Codespace using
Google's official x86_64 emulator system image, accelerated with KVM, and
shows the phone screen in your browser through noVNC on port 6080.

It follows the same pattern as the KDE Plasma branch: the Codespace boots on
GitHub's default image, and the emulator runs in a separate Docker container
built from the `Dockerfile` here.

## Start

1. Create a Codespace on this branch. A **4-core** machine is recommended.
2. The emulator starts automatically when the Codespace is ready. The first
   run builds the image, which downloads the Android SDK and the Android 17
   system image (about 2.5 GB) and takes several minutes. To start it by
   hand, or after stopping it:

   ```bash
   ./start.sh
   ```

3. Watch it come up:

   ```bash
   docker logs -f android
   ```

4. Open port **6080** from the **Ports** tab (add it with *Forward a Port*
   if it is not listed) and click **Connect**. The first boot of a fresh
   Android takes a few minutes; later boots are faster.

## Stop / start

```bash
docker compose stop
docker compose start
```

Android's disk, settings and installed apps live in `./data/` (ignored by
Git) and persist across Codespace restarts.

## adb

The emulator's adb port is on the Codespace itself (host networking), so
from a Codespace terminal:

```bash
docker exec android adb devices
docker exec android adb install /data/some.apk
```

Put APKs in `./data/` to make them visible inside the container.

## Configuration

Edit `compose.yml`:

| Setting          | Default                                      | Meaning                          |
|------------------|----------------------------------------------|----------------------------------|
| `SYSTEM_IMAGE`   | `system-images;android-37.0;google_apis;x86_64` | Android image (build argument) |
| `DEVICE_PROFILE` | `pixel_7`                                    | AVD hardware profile             |
| `SKIN`           | `540x1200`                                   | Emulator window size             |
| `SCREEN`         | `640x1280`                                   | Virtual X screen size            |

Other Android 17 images: `system-images;android-37.2;google_apis_ps16k;x86_64`
(the 17.2 quarterly release, 16 KB page size) and
`system-images;android-37.0;google_apis_playstore;x86_64` (with Play Store,
no root). After changing the image, rebuild and recreate the AVD:

```bash
docker compose down && rm -rf data && ./start.sh
```

## Requirements and limits

- Needs `/dev/kvm`, which Codespaces machines expose. Without it the
  emulator does not start.
- Graphics are rendered in software (SwiftShader), so animations are not
  smooth, but the UI is usable.
- Networking: the container uses the host network so Android has working
  DNS; Docker's bridge network in Codespaces cannot resolve names.
- This is intended for development and testing. Keep usage within the
  [GitHub Codespaces acceptable use](https://docs.github.com/en/site-policy/github-terms/github-terms-for-additional-products-and-features#codespaces).
