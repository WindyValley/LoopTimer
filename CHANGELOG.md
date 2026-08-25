# Changelog

All notable changes to this project are documented here.
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

A structural overhaul. Behaviour is intentionally unchanged for the user, apart from the bug fixes
listed below; the work targets correctness and maintainability.

### Fixed

- **Timer no longer dies with the UI.** Timer state lived in a composable's `remember`, so the
  countdown stopped when the activity was destroyed (rotation, or the process being reclaimed)
  while the foreground notification still claimed it was running. `TimerService` now owns the
  timer.
- **Countdown drift.** The countdown accumulated `delay(1000)` decrements, so every scheduling
  hiccup was lost permanently and the timer stalled entirely while the process was frozen. It is
  now derived from a monotonic deadline (`SystemClock.elapsedRealtime()`).
- **Loop counter could overflow.** Skipping a break on the final loop incremented the counter
  without a bounds check, displaying e.g. "round 5 / 4". The invariant `currentLoop <= totalLoops`
  is now enforced by the type.
- **Foreground service crash on restart.** With `START_STICKY`, a system-initiated restart
  delivered a null intent that matched no branch, so the service started without ever calling
  `startForeground()` — tripping `ForegroundServiceDidNotStartInTimeException`. Null intents are
  now handled explicitly and the service is `START_NOT_STICKY`.
- **Notification disagreed with the timer.** Its "stop" button only killed the service without
  pausing the timer. Notification actions now drive the same controller as the UI, and the
  notification shows live remaining time, phase and loop.
- **Alarm could be permanently disabled.** If `MediaPlayer` setup threw, the "playing" flag was
  never reset, so the alarm could never sound again and the UI showed "ringing" forever. Failures
  now roll the state back.
- **One malformed record crashed the preset list.** Parsing used throwing accessors with no
  surrounding `try`/`catch`. Decoding is now defensive and skips bad entries.
- **Concurrent preset edits could lose an update** (unguarded read-modify-write). Mutations are now
  serialised.
- **Home screen edits were not persisted.** Configuration was saved only when a preset was
  selected, so manual edits were lost when the process died.
- **Editing the work duration did not update the display.** Remaining time is now derived from the
  configuration.
- **Presets could not express every configuration.** The editor capped minutes at 59 and alarm
  duration at 300 s, and could not represent "ring forever". All screens now share `TimeRanges`.
- **System bars ignored the theme.** The window theme was pinned to `Material.Light`, so the status
  bar stayed light while the app rendered its dark scheme.
- **Background start could crash.** `startForegroundService` from the background now catches
  `ForegroundServiceStartNotAllowedException` and degrades to a plain notification.

### Changed

- Layered into `domain` (pure Kotlin) / `service` (runtime state) / `data` (persistence) / `ui`.
  `MainActivity` went from 1122 lines to ~90.
- Phase progression consolidated into a single `TimerEngine`, replacing three divergent copies.
- **Phase boundaries are now scheduled with `AlarmManager.setAlarmClock`** and the visible countdown
  is drawn by the notification's platform chronometer, so the app runs no code between boundaries.
  The timer therefore keeps time while the process is idle or the device is dozing, and survives the
  process being killed. It does not create entries in the user's clock app.
- **The foreground service is now only used while the alarm is audible**, declared as
  `mediaPlayback` instead of `specialUse` (which requires a manual Play policy review). Counting no
  longer requires a live process, so no foreground slot is held merely to keep time.
- Alarm configuration modelled as a sealed `AlarmSetting`, replacing the `alarmDuration = -1`
  sentinel. Legacy values are migrated on read.
- Persistence exposed as `StateFlow`, replacing a `settings_timestamp` polled on every `ON_RESUME`
  and `recreate()` calls used to refresh the list.
- Real Material 3 colour scheme, replacing an empty `MaterialTheme {}` plus per-file hardcoded
  colours.
- Input controls deduplicated into shared components; the preset screens had their own divergent
  copies.
- User-facing strings moved to `strings.xml`; icon buttons given content descriptions.
- Preset deletion now asks for confirmation.
- Persisted data carries a schema version with a migration hook.

### Removed

- `AlarmReceiver`, which was unreachable (nothing broadcast to it) and whose `else` branch would
  have started the alarm for any unrecognised intent.
- Unused permissions: `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED`. (`USE_EXACT_ALARM` is
  now genuinely used and is granted on install for timer apps, with no runtime prompt.)
- A top-level mutable global and the 100 ms polling loop used for event delivery.
- `VERSION.kt`, which had to be manually kept in sync with `gradle.properties`.

### Added

- 65 unit tests covering the state machine, timing behaviour, and the persistence codec including
  legacy migration. The project previously had none.
- `detekt` static analysis, configured to fail on any finding, wired into CI.
- CI now runs unit tests, static analysis and lint, and runs them on pull requests — the previous
  workflow declared a `pull_request` trigger but gated every job on `event_name == 'push'`, so PRs
  were never checked.
- `README.md` documenting architecture and conventions.
- A `Logger` facade; the codebase previously had no logging at all, with failures hidden by empty
  `catch` blocks.
- Minification and resource shrinking now genuinely apply, after replacing blanket `-keep` rules
  that had negated them: the release APK is 1.18 MB versus 9.32 MB unminified (-87%), with a
  mapping file retained for deobfuscating stack traces.

### Security

- `.gitignore` now covers `*.jks`, `*.keystore` and `local.properties`.
- Release signing fails fast when credentials are incomplete instead of producing an unsigned APK.
- CI validates signing secrets before use, verifies the APK signature, and removes the keystore
  afterwards.

## [1.0.2]

- Unified three-part version numbering in `gradle.properties`.
- Light theme support for the home and preset editor screens.
- Preset management.
- Scrollable settings panel.
- Background operation with notification-driven phase advance.
