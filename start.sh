#!/usr/bin/env bash
# Build (first time) and start the Android 17 emulator container.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -e /dev/kvm ]; then
  echo "No /dev/kvm on this machine: the emulator cannot run here." >&2
  exit 1
fi

# Wait for the Docker daemon (it starts shortly after the Codespace does).
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

mkdir -p /tmp/android-data

# On a fresh Codespace, put Docker's storage on /tmp (a separate 44 GB disk)
# before building the 10 GB image, so the 32 GB main disk is not filled.
# Only done while no image exists yet, so an existing setup is left alone.
if ! docker image inspect android17-codespaces >/dev/null 2>&1 \
   && [ "$(docker info -f '{{.DockerRootDir}}' 2>/dev/null)" != "/tmp/docker-data" ] \
   && [ -x /usr/local/share/docker-init.sh ]; then
  ./move-docker-to-tmp.sh || echo "Could not move Docker storage; building on the main disk." >&2
fi

# Build with plain `docker build`: compose's bake builder refuses
# --network host without an entitlement flag it cannot be given, while
# the default builder allows it. Host networking is needed because the
# Docker bridge in Codespaces has no working DNS.
if ! docker image inspect android17-codespaces >/dev/null 2>&1; then
  echo "Building the emulator image (downloads the SDK and Android 17 image, about 6 GB; 10-15 minutes)..."
  docker build --network host -t android17-codespaces .
fi
docker compose up -d

echo
echo "Emulator starting. Open port 6080 in the Ports tab (add it with"
echo "'Forward a Port' if it is not listed). Follow progress with:"
echo "  docker logs -f android"
