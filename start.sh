#!/usr/bin/env bash
# Start the KDE Plasma VM.
set -euo pipefail
cd "$(dirname "$0")"

# Wait for the Docker daemon (it starts shortly after the Codespace does).
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

docker compose up -d

echo
echo "VM starting. Open forwarded port 8006 in the Ports tab."
echo "Follow progress with:  docker logs -f plasma"
