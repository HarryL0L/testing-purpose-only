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

mkdir -p data

if ! docker image inspect android17-codespaces >/dev/null 2>&1; then
  echo "Building the emulator image (downloads about 2.5 GB, takes several minutes)..."
fi
docker compose up -d --build

echo
echo "Emulator starting. Open port 6080 in the Ports tab (add it with"
echo "'Forward a Port' if it is not listed). Follow progress with:"
echo "  docker logs -f android"
