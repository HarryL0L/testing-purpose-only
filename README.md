# Mochi

A soft, squishy Dynamic Island–style overlay for Android 10+ phones with a hole-punch camera.
Mochi wraps the camera in a black pill that fluidly morphs to show:

| Trigger | Compact pill | Expanded card |
| --- | --- | --- |
| Music session (Spotify, YouTube Music, …) | album art + animated bars | art, title, artist, progress, ⏮ ⏯ ⏭ |
| Bluetooth earbuds or headphones (e.g. Sony WF‑C500) | earbud icon + battery % | battery ring, name, "Connected · 82%" |
| Wired headphones plugged in / removed | headset icon | "Connected" / "Disconnected" |
| Charger connected / removed | bolt + % | pulsing bolt, battery ring, "Charging" |
| App notifications | app icon + title | app icon, title, two lines of text; tap opens, swipe up dismisses |
| Live activities: stopwatch, timer, calls, downloads, navigation | icon + running time / % | title, text, big running clock or progress bar |

While music plays with earbuds connected, the earbud battery sits in a small **companion bubble**
that splits off from the music pill, like the iOS island. When a stopwatch runs during music, the
stopwatch takes the pill and the album art moves into the bubble.

## Fits the camera automatically

The island reads the screen size and the camera cutout straight from Android (`DisplayCutout`,
using the exact cutout shape on Android 12+). The pill is the camera height plus an even ring, and
its rounded end is concentric with the camera. It re-fits on rotation, resolution and display-size
changes. Without a top camera, for example in landscape, it floats at the top centre. The settings
screen shows exactly what Android reported. The nudge sliders are only for fine-tuning.

Some phones (for example Realme and OPPO models) report a hole-punch camera as a padded box running into the screen corner (about
150 × 108 px from the top-left corner on a 1080 px wide screen), not as the hole itself. A real hole never
touches the screen edge, so the island treats such a box as padding and estimates the hole inside
it. For an exact fit, tap **Line up with camera**: drag a ring onto the black camera hole on a
bright full-screen canvas, size it, and save. The line-up is stored relative to screen width, so
it survives resolution changes.

## Why Accessibility is needed for taps

Android draws normal app overlays *below* the status bar, so the status bar takes every tap on the
island. Enabling **Accessibility → Mochi** moves it to an accessibility overlay above the
status bar, so tap-to-expand, the media buttons and swipe-to-dismiss work. The service reads no
screen content. It only watches window changes to hide the island while the notification shade or
lock screen is open. On Android 13+ a sideloaded app's accessibility switch can be greyed out:
open App info → ⋮ → **Allow restricted settings**, then turn it on.

## Material 3 Expressive

* The settings app uses the Material 3 Expressive theme from Material Components 1.14: a large
  collapsing title, grouped rounded-segment lists, a connected button group for the position
  picker, and Expressive buttons that change shape when pressed.
* Material You: on Android 12+ the app takes its colours from your wallpaper, and the island's own
  highlights can use the wallpaper accent too (Behaviour → Material You accent colours).
* The island moves with the Material 3 Expressive motion scheme springs, and the music card shows
  the Expressive wavy progress bar while playing.
* The launcher icon includes a monochrome layer for Android 13+ themed icons.

## Battery design

* **No polling.** Everything is driven by broadcasts (Bluetooth profile state, headset plug,
  charger, screen on/off) and MediaSession callbacks.
* **Zero overlay cost when idle.** The window is removed from the WindowManager when there is
  nothing to show, and whenever the screen turns off.
* **Tight window.** The overlay window is sized to the pill, so the status bar and shade gestures
  keep working and nothing is composited beyond the pill.
* Spring animations settle and stop; the only continuous animation is the 3‑bar visualizer
  (≈11 fps, compact music pill only, can be switched off).
* The overlay window is only resized when the island's shape really changes, and its camera-side
  edge never moves, so frequent media, battery and timer updates cause no jitter.
* Stopwatch and timer pills redraw once per second, exactly on the second.
* Album art is downscaled once per track; the accent colour is extracted off the main thread.
* Foreground service notification uses the lowest importance channel.

## Project layout

```
app/src/main/java/com/harryl/island/
  IslandView.kt               Canvas renderer + spring morphing + gestures
  IslandLayout.kt             Pure camera-fitting maths (unit tested)
  IslandGeometry.kt           Screen size, status bar and camera cutout from Android
  IslandOverlay.kt            WindowManager host, window sizing, display-change refits
  IslandAccessibilityService.kt  Above-status-bar overlay host + shade detection
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
./gradlew testDebugUnitTest   # camera-fitting tests
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, compileSdk 34.

## Setup on the phone

1. Open **Mochi** and grant, in order:
   * Accessibility (needed for taps; see above)
   * Notification access (needed for music, notifications and live activities)
   * Nearby devices (Bluetooth) – Android 12+
   * Show service notification – Android 13+
   * Unrestricted battery
   * Display over other apps (only a fallback if Accessibility stays off)
2. Turn on **Island enabled**.
3. Tap a **Preview** chip to see each style. Previews never change the real device state.

### Keeping Mochi awake

Some phones close background apps aggressively. Also do:

* App info → **Battery usage → Allow background activity**
* App info → **Auto‑launch → On**
* Settings → Battery → App battery management → Mochi → **Don't optimise**
* In recents, swipe down on Mochi's card and tap **Lock**

The notification‑listener service doubles as an auto‑start hook: the system binds it at boot,
which restarts the island even when `BOOT_COMPLETED` is blocked.

## WF‑C500 battery

Sony earbuds report battery over the HFP profile (`AT+IPHONEACCEV`). Android exposes it through
the hidden `BluetoothDevice.getBatteryLevel()` / `BATTERY_LEVEL_CHANGED` broadcast, which this
app reads with reflection. It works on Android 10–14. If the percentage shows as
"Connected" without a number, the device didn't report a level yet; it updates as soon as the
earbuds send one.

Connected devices are seeded from the Bluetooth profiles' real connected-device lists when the
island starts, and cleared when Bluetooth turns off, so a device can never get stuck as "connected".
Wired and USB‑C earphones and dongles are detected through Android's audio-device callback,
because many phones have no 3.5 mm jack.
