# Realme Island

A Dynamic Island–style overlay for the **Realme X7 Max** (and any Android 10+ phone).
It wraps the hole‑punch camera in a black pill that fluidly morphs to show:

| Trigger | Compact pill | Expanded card |
| --- | --- | --- |
| Music session (Spotify, YouTube Music, …) | album art + animated bars | art, title, artist, progress, ⏮ ⏯ ⏭ |
| Sony WF‑C500 / any Bluetooth earbuds or headphones | earbud icon + battery % | battery ring, name, "Connected · 82%" |
| Wired headphones plugged in / removed | headset icon | "Connected" / "Disconnected" |
| Charger connected / removed | bolt + % | pulsing bolt, battery ring, "Charging" |
| App notifications | app icon + title | app icon, title, two lines of text; tap opens, swipe up dismisses |

While music plays with earbuds connected, the earbud battery sits in a small **companion bubble**
that splits off from the music pill, like the iOS island.

## Battery design

* **No polling.** Everything is driven by broadcasts (Bluetooth profile state, headset plug,
  charger, screen on/off) and MediaSession callbacks.
* **Zero overlay cost when idle.** The window is removed from the WindowManager when there is
  nothing to show, and whenever the screen turns off.
* **Tight window.** The overlay window is sized to the pill, so the status bar and shade gestures
  keep working and nothing is composited beyond the pill.
* Spring animations settle and stop; the only continuous animation is the 3‑bar visualizer
  (≈11 fps, compact music pill only, can be switched off).
* Album art is downscaled once per track; the accent colour is extracted off the main thread.
* Foreground service notification uses the lowest importance channel.

## Project layout

```
app/src/main/java/com/harryl/island/
  IslandView.kt               Canvas renderer + spring morphing + gestures
  IslandOverlay.kt            WindowManager host, cutout detection, window sizing
  IslandService.kt            Foreground service / state machine
  MediaWatcher.kt             MediaSessionManager → IslandContent.Music
  DeviceReceiver.kt           Bluetooth, wired headset, charger, screen broadcasts
  IslandNotificationListener.kt  Notification access + app notifications
  MainActivity.kt             Setup checklist, position tuning, previews
  Prefs.kt / IslandModels.kt  Settings and data classes
```

## Build

Open the folder in Android Studio (Ladybug or newer) and run, or:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, compileSdk 34.

## Setup on the phone

1. Open **Realme Island** and grant, in order:
   * Display over other apps
   * Notification access (needed for music sessions *and* notifications)
   * Nearby devices (Bluetooth) – Android 12+
   * Show service notification – Android 13+
   * Unrestricted battery
2. Turn on **Island enabled**.
3. Tap a **Preview** chip (WF‑C500, Music, …) and use the *Position* sliders until the pill hugs
   the camera hole. "Auto" uses the system cutout; "Left camera hole" is a hard‑coded X7 Max fallback.

### Realme UI (ColorOS) background survival

Realme UI kills background apps aggressively. Also do:

* App info → **Battery usage → Allow background activity**
* App info → **Auto‑launch → On**
* Settings → Battery → App battery management → Realme Island → **Don't optimise**
* In recents, swipe down on the Realme Island card and tap **Lock**

The notification‑listener service doubles as an auto‑start hook: the system binds it at boot,
which restarts the island even when `BOOT_COMPLETED` is blocked.

## WF‑C500 battery

Sony earbuds report battery over the HFP profile (`AT+IPHONEACCEV`). Android exposes it through
the hidden `BluetoothDevice.getBatteryLevel()` / `BATTERY_LEVEL_CHANGED` broadcast, which this
app reads with reflection. It works on Android 10–14 on Realme UI. If the percentage shows as
"Connected" without a number, the device didn't report a level yet; it updates as soon as the
earbuds send one.
