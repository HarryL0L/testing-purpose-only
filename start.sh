#!/usr/bin/env bash
# Start the KDE Plasma VM.
set -euo pipefail
cd "$(dirname "$0")"

# Pinned KDE neon User Edition ISO (Plasma 6.8.1). For the newest neon build
# use https://files.kde.org/neon/images/desk/user/current/neon-user-desk-current.iso
ISO_URL="${ISO_URL:-https://files.kde.org/neon/images/desk/user/20261025-1825/neon-user-desk-20261025-1825.iso}"
ISO="storage/neon.iso"

mkdir -p storage

# Download the ISO on the Codespace itself: DNS does not work inside the
# VM container, but it does here. Resumes if interrupted.
if [ ! -s "$ISO" ]; then
  echo "Downloading KDE neon ISO (about 3.4 GB)..."
  curl -L --fail --retry 5 -C - -o "$ISO.part" "$ISO_URL"
  mv "$ISO.part" "$ISO"
fi

# Wait for the Docker daemon (it starts shortly after the Codespace does).
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

docker compose up -d

echo
echo "VM starting. Open forwarded port 8006 in the Ports tab."
echo "Follow progress with:  docker logs -f plasma"
