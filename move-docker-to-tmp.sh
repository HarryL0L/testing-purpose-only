#!/usr/bin/env bash
# Move Docker's storage to /tmp/docker-data.
#
# In Codespaces, /, /workspaces and /var/lib/docker share one 32 GB disk,
# while /tmp is a separate 44 GB disk. The emulator image is about 10 GB,
# which fills the small disk. This points Docker at /tmp instead, the same
# trick the "Windows in Codespaces" guides use.
#
# Images and containers already in the old location are not carried over;
# ./start.sh rebuilds the emulator image in the new location.
set -euo pipefail

ROOT=/tmp/docker-data
INIT=/usr/local/share/docker-init.sh

current=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo unknown)
if [ "$current" = "$ROOT" ]; then
  echo "Docker already stores its data in $ROOT."
  exit 0
fi

echo "Docker root is $current; moving to $ROOT."
docker compose down 2>/dev/null || true
sudo mkdir -p "$ROOT"

# Write (or merge into) /etc/docker/daemon.json.
if [ -s /etc/docker/daemon.json ]; then
  sudo python3 - "$ROOT" <<'PY'
import json, sys
p = "/etc/docker/daemon.json"
d = json.load(open(p))
d["data-root"] = sys.argv[1]
json.dump(d, open(p, "w"), indent=2)
PY
else
  sudo mkdir -p /etc/docker
  printf '{\n  "data-root": "%s"\n}\n' "$ROOT" | sudo tee /etc/docker/daemon.json >/dev/null
fi

# Restart the Docker daemon the way Codespaces starts it.
sudo pkill dockerd 2>/dev/null || true
sudo pkill containerd 2>/dev/null || true
sleep 3
if [ -x "$INIT" ]; then
  "$INIT"
else
  echo "Docker init script not found at $INIT." >&2
  echo "Restart the Codespace (Command Palette -> Codespaces: Stop Current Codespace, then reopen) to apply." >&2
  exit 1
fi
for _ in $(seq 1 30); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

echo "Docker root is now $(docker info -f '{{.DockerRootDir}}')."
echo
echo "The old data in $current still takes space on the small disk."
echo "Reclaim it with:  sudo rm -rf $current"
