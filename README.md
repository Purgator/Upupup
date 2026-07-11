# Upupup ⏰

**A simple, lightweight alarm clock that will get you out of bed — guaranteed.**

Upupup is a free, open-source Android alarm app for people who have real trouble waking
up. It is inspired by the alarm part of Alarmy: a rock-solid alarm you can trust, a
clear "rings in 7h 32m" everywhere, and wake-up missions that force your brain on
before the noise stops.

No ads, no account, no paid tier, no tracking. ~4.5 MB.

## Features

- **Guaranteed ring** — alarms use Android's `setAlarmClock()` API: exact to the
  minute, exempt from Doze and battery optimizations, re-armed automatically after a
  reboot or an app update. A foreground service keeps the sound looping even if the
  app is closed or the phone is locked.
- **You always know when it rings** — a "Rings in Xh Ym" banner on the alarm list,
  a live preview while editing, a toast when you save, and the system alarm icon in
  the status bar.
- **Wake-up missions** — to stop the alarm you can require:
  - *Shake* — shake the phone hard, 10 to 100 times (peak detection: lazy wiggles don't count)
  - *Math* — solve 3 problems (easy / medium / hard)
  - *Typing* — retype 1 to 3 wake-up phrases
  - *Steps* — get up and walk 10 to 50 steps
  - *Memory* — repeat a Simon-style sequence of flashing tiles
- **Full alarm setup** — repeat days, label, any alarm sound, per-alarm volume,
  gradually increasing volume, vibration, snooze duration (1–30 min) with an optional
  max snooze count.
- **Sound output device** — route the alarm to the phone speaker, wired headphones or
  a Bluetooth device (great for not waking a partner).
- **Countdown timer** — with a live notification and the same guaranteed ring.
- **Auto-update** — checks GitHub once a day and updates itself (downloads on Wi-Fi
  only, and Android verifies the signature before installing). Can be disabled in
  settings.
- English and French.

## Install (for everyone)

1. On your Android phone (Android 8.0+), open
   **[the latest release](https://github.com/Purgator/Upupup/releases/latest)**.
2. Download **`Upupup.apk`**.
3. Open the downloaded file. If the phone warns about unknown apps, allow your browser
   or file manager to install apps, then confirm.
4. Open Upupup and allow **notifications** when asked (that's how the alarm rings).
5. Create your first alarm with **+**. Done — future updates install themselves.

> 💡 If your phone has an aggressive battery saver (Xiaomi, Huawei…), also allow
> Upupup to auto-start / run in the background so nothing can delay the alarm.

## For developers

### Build

```bash
git clone https://github.com/Purgator/Upupup.git
cd Upupup
# point local.properties to your Android SDK:
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests
./gradlew assembleRelease        # release APK (signed if keystore.properties exists)
```

Requirements: JDK 17, Android SDK 34. Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9 come from
the wrapper.

### Release signing

Releases are signed with a keystore that is **not** in the repo. To sign your own
builds, create `release.keystore` at the repo root and a `keystore.properties`:

```properties
storeFile=../release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

### Architecture

Plain Kotlin + Views (no Compose, no DI, no Room — the app stays tiny and fast).
Alarms are JSON in `SharedPreferences`.

| Piece | Role |
|---|---|
| `core/Alarm` | Model + next-trigger computation |
| `core/AlarmScheduler` | `AlarmManager.setAlarmClock()` registration |
| `core/AlarmReceiver` | Exact-time trigger → starts the service, re-arms repeats |
| `core/AlarmService` | Foreground service: sound, vibration, output routing, ramp, auto-snooze timeout |
| `core/BootReceiver` | Re-arms everything after reboot / update / time change |
| `RingActivity` | Lock-screen ringing UI + the five wake-up missions |
| `core/UpdateManager` | Daily GitHub-release check, background download, self-install |

### Publishing a release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`
3. Upload the APK as `Upupup.apk` on a GitHub release tagged `vX.Y` — installed apps
   pick it up within a day.

## License

[MIT](LICENSE)
