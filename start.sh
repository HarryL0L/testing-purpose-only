#!/usr/bin/env bash
# Start the KDE Plasma VM. Uses KVM when the host has it, otherwise
# falls back to QEMU software emulation (much slower, but it boots).
set -euo pipefail
cd "$(dirname "$0")"

# Wait for the Docker daemon (it starts shortly after the Codespace does).
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

if [ -e /dev/kvm ] && [ -w /dev/kvm ]; then
  echo "KVM found: starting with hardware acceleration."
  docker compose -f compose.yml -f compose.kvm.yml up -d
else
  echo "No usable /dev/kvm: starting in software emulation (about 10x slower)."
  KVM=N docker compose -f compose.yml up -d
fi

echo
echo "VM starting. Open forwarded port 8006 in the Ports tab."
echo "Follow progress with:  docker logs -f plasma"
