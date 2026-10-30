#!/bin/bash
# Create the AVD on first run, then start Xvfb, VNC, noVNC and the emulator.
set -euo pipefail

: "${AVD_NAME:=android17}"
: "${DEVICE_PROFILE:=pixel_7}"
: "${SKIN:=540x1200}"
: "${SCREEN:=640x1280}"
: "${EMULATOR_ARGS:=}"
: "${NOVNC_PORT:=6080}"

mkdir -p "$ANDROID_AVD_HOME"

if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
  echo "Creating AVD $AVD_NAME from $SYSTEM_IMAGE ..."
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d "$DEVICE_PROFILE" --force
  cat >> "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini" <<CFG
hw.keyboard=yes
hw.ramSize=2048
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=6G
CFG
fi

# Virtual X display that the emulator window renders into.
Xvfb :0 -screen 0 "${SCREEN}x24" -nolisten tcp >/tmp/xvfb.log 2>&1 &
for _ in $(seq 1 50); do xdpyinfo -display :0 >/dev/null 2>&1 && break; sleep 0.2; done

# VNC server on the X display, and the noVNC web client in front of it.
x11vnc -display :0 -forever -shared -nopw -quiet -localhost -rfbport 5900 >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc "$NOVNC_PORT" localhost:5900 >/tmp/novnc.log 2>&1 &

echo "Starting emulator (KVM: $([ -w /dev/kvm ] && echo yes || echo NO))"
exec emulator "@$AVD_NAME" \
  -gpu swiftshader_indirect -accel on \
  -skin "$SKIN" -no-audio -no-boot-anim -no-snapshot \
  $EMULATOR_ARGS
