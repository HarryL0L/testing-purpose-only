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

# Emulator UI settings: no "nested virtualization" dialog, no "host RAM is
# below the suggested 16 GB" dialog for this AVD, no crash-report consent.
mkdir -p "$HOME/.config/Android Open Source Project"
cat > "$HOME/.config/Android Open Source Project/Emulator.conf" <<CONF
[General]
showNestedWarning=false
showCompatibilityWarning_${AVD_NAME}=false

[set]
crashReportPreference=2
autoFindAdb=true
CONF
# An unclean stop leaves a crash record that triggers a consent dialog on
# the next start; drop it.
find /tmp -maxdepth 1 -name 'android-*' -type d -exec rm -rf {} + 2>/dev/null || true

# Virtual X display that the emulator window renders into.
Xvfb :0 -screen 0 "${SCREEN}x24" -nolisten tcp >/tmp/xvfb.log 2>&1 &
for _ in $(seq 1 50); do xdpyinfo -display :0 >/dev/null 2>&1 && break; sleep 0.2; done

# VNC server on the X display, and the noVNC web client in front of it.
x11vnc -display :0 -forever -shared -nopw -quiet -localhost -rfbport 5900 >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc "$NOVNC_PORT" localhost:5900 >/tmp/novnc.log 2>&1 &

# The emulator opens its window at a small scale; once it appears, move it
# to the top-left corner and enlarge it to fill the screen (the toolbar
# sits to the right of the window).
(
  W=${SCREEN%x*}; H=${SCREEN#*x}
  for _ in $(seq 1 300); do
    sleep 1
    id=$(xdotool search --name '^Android Emulator - ' 2>/dev/null | head -1) || true
    if [ -n "$id" ]; then
      xdotool windowmove "$id" 0 0 windowsize "$id" $((W - 80)) $((H - 40)) 2>/dev/null || true
      break
    fi
  done
) &

adb start-server >/dev/null 2>&1 || true

echo "Starting emulator (KVM: $([ -w /dev/kvm ] && echo yes || echo NO))"
exec emulator "@$AVD_NAME" \
  -gpu swiftshader_indirect -accel on \
  -skin "$SKIN" -no-audio -no-boot-anim -no-snapshot -no-metrics \
  $EMULATOR_ARGS
