# CLAUDE.md — project guide for AI assistants

Upupup is a lightweight, free Android alarm-clock app (Kotlin + classic Views, no
Compose/DI/Room) inspired by Alarmy: a guaranteed ring plus "wake-up missions" you must
complete to stop the alarm. Owner: Aymeric (@Purgator), personal project, French user —
every user-facing string exists in EN (`values/strings.xml`) and FR (`values-fr/`).

## Non-negotiable invariants

1. **The alarm must always ring.** Alarms register via `AlarmManager.setAlarmClock()`
   (exact, Doze-exempt). `BootReceiver` re-arms on boot/update/time change. Repeating
   alarms re-arm their next occurrence *at fire time* in `AlarmReceiver`, before the
   ring UI even starts. Never weaken this path.
2. **Missions must not be cheatable.** Shake uses hysteresis + refractory
   (`ShakeDetector`: >3.2g trigger, <1.6g reset, 400ms window = one physical shake, one
   count). Steps rejects shake-spikes via a parallel accelerometer watch. If a mission's
   sensor/permission is missing at ring time, fall back to shake — never to "just stop".
3. **Auto-update contract.** `UpdateManager` polls
   `api.github.com/repos/Purgator/Upupup/releases/latest` and matches the asset named
   exactly **`Upupup.apk`** on a tag named **`vX.Y`**. Release process must keep both.
4. Manual update checks download on ANY network; the Wi-Fi-only rule is for the
   automatic daily check only.

## Build / test / release

```bash
./gradlew assembleDebug          # debug build
./gradlew test                   # JVM unit tests (~18, must stay green)
./gradlew lintDebug              # lint gate — zero errors expected
./gradlew assembleRelease        # signed if keystore.properties present
```

- JDK 17. SDK path in `local.properties` (gitignored) →
  `C:/dev/Perso/AdBlocker4Android/.tools/android-sdk` on the dev machine (shared with
  the DNS67 project). Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24, minSdk 26, target 34.
- Signing: `release.keystore` + `keystore.properties` at repo root, **gitignored**.
  They are a copy of the DNS67 certificate — recover from
  `C:/dev/Perso/AdBlocker4Android/` if missing. Never commit them.
- No emulator/device is available in the dev environment: verification = build + unit
  tests + lint, and flag anything that really needs an on-device check in the summary.

**Release steps** (owner asks for releases explicitly):
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; commit `🔖 Version X.Y`.
2. `./gradlew assembleRelease`, push `master:main` (local branch is `master`, remote default is `main`).
3. `gh release create vX.Y <apk renamed to Upupup.apk> --repo Purgator/Upupup --target main --title "Upupup X.Y" --notes "…"`.

## Conventions

- **Gitmoji commit messages** (🎉 ✨ 🐛 💄 🔊 ✅ 📝 🔖 ⚡ 🚨 …), one logical feature per
  commit, imperative summary. Commits end with the Claude co-author trailer.
- Small, dependency-light code. Persistence = JSON in `SharedPreferences` (no DB).
- Every new user-facing string goes to both `values/strings.xml` and `values-fr/`.
- Add/adjust unit tests for pure logic (trigger math, JSON round-trip + legacy
  migrations, mission generators, version comparison).
- JSON schema changes to `Alarm` need a legacy-migration branch in `fromJson` + a test
  (see `output`/`routinePackage` migrations for the pattern).

## Architecture map (`app/src/main/java/fr/arichard/upupup/`)

| File | Role |
|---|---|
| `core/Alarm.kt` | Model + `nextTrigger()` + JSON (enums: `Mission`, `Output`, `RoutineType`) |
| `core/AlarmStore.kt` | Alarm list + snooze state (SharedPreferences) |
| `core/AlarmScheduler.kt` | `setAlarmClock` registration + pre-alarm heads-up scheduling |
| `core/AlarmReceiver.kt` | Fire + pre-alarm notification; re-arms repeats; starts service |
| `core/AlarmService.kt` | Foreground ring: sound (per-alarm volume forced on alarm stream, ramp, output routing), vibration, full-screen intent, 5-min auto-snooze timeout, `current` static read by the UI |
| `core/BootReceiver.kt` | Re-arm after boot/update/clock change |
| `core/TimerStore.kt` / `StopwatchStore.kt` | Countdown & stopwatch persistence (stopwatch uses `elapsedRealtime`) |
| `core/Prefs.kt` | App settings (swipe snooze, default output, pre-alarm lead, time-picker mode…) |
| `core/RoutineRunner.kt` | After-stop routine: open app / TTS phrase / assistant query |
| `core/UpdateManager.kt` + `ApkInstaller.kt` | GitHub-release self-update (PackageInstaller session, unknown-sources handling) |
| `mission/ShakeDetector.kt`, `StepDetector.kt`, `MathMission.kt`, `MemoryMission.kt` | Mission engines |
| `MainActivity.kt` | Bottom nav: Alarms (list + fixed 40dp next-ring banner) / Timer (preset +time buttons) / Stopwatch; permission onboarding; 1s ticker |
| `AlarmEditActivity.kt` | Editor: wheel-or-clock time picker, day toggles, mission bottom sheet (illustrated cards + chips + "Try it" preview), snooze/output/routine dialogs, unsaved-changes diff dialog |
| `RingActivity.kt` | Lock-screen ring UI: slide-to-stop (no mission) or big start-mission button; swipe-up-to-snooze (animated helper) or button; the five missions; shake fills screen bottom→top + haptic blip; preview mode via `EXTRA_PREVIEW_*` |
| `SettingsActivity.kt` | Output default, time-picker style, pre-alarm lead, swipe snooze, auto-update + manual check/install |

## UX decisions already settled (don't relitigate)

- Mission alarms: big **Start mission** button; completing the mission stops the alarm.
  Slide-to-stop only for mission-less alarms. Snooze = swipe-up by default (setting for
  a button instead).
- Ring screen pops instantly via the **overlay permission** (`SYSTEM_ALERT_WINDOW`,
  prompted on app open) + full-screen intent as fallback; activities also call
  `RingActivity.openIfRinging` on resume/tick.
- Theme: "comfy" warm cream (light) / candle-lit brown (dark); ring screen keeps a fixed
  dark gradient with `ring_accent` orange. Bottom nav icons+labels, accent when active.
- Next-alarm banner: fixed 40dp single line.
- Sound output: global setting, per-alarm override, effective output icon always shown
  on each alarm card.

## Known sharp edges

- `RingActivity` is `singleInstance`; previews convert to a real ring via `onNewIntent`.
- Notification ids: 1 ring, 2 update, 3 timer, 4 stopwatch, 100+ pre-alarm heads-ups.
- `AlarmService.stopRinging()` restores the user's alarm-stream volume — keep symmetric.
- Wheel time picker: values typed but not committed are flushed in
  `AlarmEditActivity.currentDraft()` via `clearFocus()`.
- Shake feel is tuned by three constants in `ShakeDetector`; the owner tests on a real
  device and gives feedback — expect requests to retune.
- OEM battery managers (Xiaomi…) may delay the pre-alarm (`setExactAndAllowWhileIdle`);
  the real alarm is unaffected (`setAlarmClock`).
