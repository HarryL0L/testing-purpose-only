# Android 17 emulator (Google's official x86_64 system image) with KVM,
# shown in the browser through noVNC. Built on the Codespace by start.sh.
FROM ubuntu:24.04

ARG SYSTEM_IMAGE="system-images;android-37.0;google_apis;x86_64"
ARG CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openjdk-17-jre-headless unzip curl \
      xvfb x11vnc novnc websockify x11-utils xdotool \
      libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 libgbm1 \
      libpulse0 libnss3 libasound2t64 \
      libxcomposite1 libxcursor1 libxi6 libxtst6 libxkbcommon0 libxkbfile1 \
      libxdamage1 libxrandr2 libxfixes3 libxrender1 libxcb-cursor0 \
      libdbus-1-3 libfontconfig1 \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip "$CMDLINE_TOOLS_URL" \
 && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip

ENV PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# Emulator, adb and the Android 17 system image (about 6 GB installed).
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platform-tools" "emulator" "$SYSTEM_IMAGE"

ENV SYSTEM_IMAGE="$SYSTEM_IMAGE" \
    ANDROID_AVD_HOME=/data/avd \
    HOME=/root \
    DISPLAY=:0

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5900 6080
ENTRYPOINT ["/entrypoint.sh"]
