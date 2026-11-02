# WristLink

**Use a Samsung Galaxy Watch (Wear OS) with an iPhone.** Notifications with app icons and full
text, calls on the watch, music controls, a camera shutter, connection stats, a sleep quiet mode,
and an optional iPhone app for Apple Health and more.

Samsung and Google don't support Galaxy Watch 4 and newer on iPhone. WristLink makes them work by
talking to the Bluetooth services iOS already offers every accessory, the same way Garmin and
other wearables do, and adds an iPhone companion app for what iOS doesn't share on its own.

> WristLink is a fork of [WatchBridge](https://github.com/WasThatRudy/watchbridge) by Raj Tiwari,
> which did the hard groundwork on iOS's notification and media services. The original
> documentation is in [docs/upstream-README.md](docs/upstream-README.md).

## What works

| | Needs the iPhone app? |
|---|---|
| iPhone notifications on the watch, with title, text and app icon, usually under a second | No |
| Calls: ring on the watch, answer or decline, talk through the watch | No |
| Music controls for whatever plays on the iPhone (Spotify, Apple Music, ...) | No |
| Camera shutter for the iPhone camera | No |
| iPhone battery level on the watch | No |
| Connection stats: notification delay, uptime, drops (Settings → Connection stats) | No |
| Quiet while asleep: Do Not Disturb and no raise-to-wake at night | No |
| Watch battery and stats on the iPhone | Yes |
| Apple Health sync (steps, heart rate, sleep, ...) | Yes, [in progress](#roadmap) |

Not possible on iPhone for any third-party watch: replying to messages, call history, Apple Pay.

Tested on a **Galaxy Watch4 Classic (SM-R890), One UI 8 Watch / Wear OS 6**, with an iPhone on iOS 26.
Should work on other Galaxy and Wear OS watches.

## Install

### Watch

You need a computer with [adb](https://developer.android.com/tools/releases/platform-tools) once.
The watch and the computer must be on the same Wi-Fi.

1. On the watch: **Settings → About watch → Software → tap Software version** until developer mode
   is on, then **Settings → Developer options → ADB debugging** and **Wireless debugging** on.
2. Run the installer and follow it (it pairs adb, installs the latest release and grants the
   permissions a watch has no screen for):
   - macOS / Linux: `bash scripts/install.sh`
   - Windows: `scripts\install.bat`
3. On the watch, open **WristLink → Start pairing**. On the iPhone, open **Settings → Bluetooth**
   and tap **WristLink** (it can be listed twice; either works). Allow notifications when asked.

Turn off Bluetooth on any Android phone the watch was set up with while pairing, or Wear OS may
hide the watch from the iPhone.

The permissions the installer grants, for reference:

```sh
dumpsys deviceidle whitelist +com.watchbridge                 # stay connected in the background
cmd appops set com.watchbridge RUN_ANY_IN_BACKGROUND allow
cmd appops set com.watchbridge REQUEST_INSTALL_PACKAGES allow  # in-app updates
pm grant com.watchbridge android.permission.ACTIVITY_RECOGNITION    # sleep detection
cmd notification allow_dnd com.watchbridge                          # quiet while asleep
pm grant com.watchbridge android.permission.WRITE_SECURE_SETTINGS   # watch settings from WristLink / iPhone
cmd appops set com.watchbridge WRITE_SETTINGS allow
pm grant com.watchbridge android.permission.BODY_SENSORS              # heart rate (Wear OS 6 also: health.READ_HEART_RATE)
```

### iPhone app (optional)

No Mac needed: it is built on GitHub and installed with AltStore. See [ios/README.md](ios/README.md).

## Battery

WristLink itself used **0.2%** of the watch's overnight drain in a measured night (batterystats).
The link to the iPhone runs at a low-power Bluetooth interval (120 ms) and sleep detection reads
the step counter every 10 minutes. On a Galaxy Watch most overnight drain comes from Samsung
Health's sleep measurements (blood oxygen especially), Bluetooth and the screen.

## Roadmap

- **iPhone app:** Apple Health sync, watch info and settings from the iPhone, album art, app icons
  and weather fetched by the iPhone so the watch needs no Wi-Fi, ring phone / ring watch.
- **Sleep:** better detection, sleep sessions to Apple Health.
- **Workouts:** record on the watch, Apple Health with route, Strava upload.
- **Don't buzz while the iPhone is in use.**

## Project layout

```
app/        Wear OS app (Kotlin, Compose for Wear OS)
ios/        iPhone companion app (SwiftUI, generated with XcodeGen)
scripts/    Watch installers
docs/       Upstream documentation and notes
```

Watch builds: `./gradlew assembleDebug`. Releases are signed with the `KEYSTORE_*` environment
variables (see `app/build.gradle.kts`). iPhone builds run in GitHub Actions
(`.github/workflows/ios.yml`) and land in the
[`ios-latest`](https://github.com/slymn08183/WristLink/releases/tag/ios-latest) pre-release.

## License

MIT, see [LICENSE](LICENSE). Based on WatchBridge, © 2026 Raj Tiwari.
