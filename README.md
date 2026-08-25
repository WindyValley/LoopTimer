# LoopTimer

An interval timer for Android: alternating work and break phases over a configurable number of
loops, with optional alarm and saveable presets.

## Building

Requires JDK 17+ and the Android SDK (API 35). Point Gradle at your SDK by creating
`local.properties` in the repository root — it is gitignored and must never be committed:

```properties
sdk.dir=/path/to/android-sdk
```

| Task | Command |
| --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` |
| Static analysis | `./gradlew detekt` |
| Lint | `./gradlew lintDebug` |
| Debug APK | `./gradlew assembleDebug` |
| Everything CI runs | `./gradlew testDebugUnitTest detekt lintDebug assembleDebug` |

The debug variant uses the `.debug` application id suffix, so it installs alongside a release
build.

### Release builds

A release build **requires complete signing credentials and fails fast without them**, rather
than silently emitting an unsigned APK:

- `app/keystore.jks`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

CI decodes the keystore from the `ANDROID_SIGNING_KEY_BASE64` secret, verifies the signature with
`apksigner`, and deletes the keystore afterwards.

### Versioning

`gradle.properties` is the single source of truth:

```properties
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=2
```

`versionName` is derived from these and `versionCode` is `MAJOR*10000 + MINOR*100 + PATCH`.
Kotlin code reads `BuildConfig.VERSION_NAME`; never hardcode a version string.

## Architecture

The layers exist so the timer logic is testable without an Android device.

```
domain/    Pure Kotlin. No Android imports. Fully unit tested.
service/   Owns authoritative runtime state. Drives the countdown and notifications.
data/      Persistence, exposed as observable state.
ui/        Renders state and forwards intents. Owns no timer state.
```

### Key decisions

**`TimerEngine` is the single state machine.** Phase progression previously existed in three
separate copies (the countdown coroutine, the skip action, and the notification-resume path) which
had diverged — one incremented the loop counter unconditionally and could display "round 5 / 4".
`TimerEngine` is a pure function returning the next state plus the side effects to perform; the
service decides how to carry them out.

**`TimerService` owns the timer, not the UI.** State used to live in a composable's `remember`, so
the countdown died with the activity while the foreground notification still claimed it was
running. The service now holds the controller and publishes `StateFlow`; the UI only observes. The
notification's actions drive the same controller, so it can no longer disagree with the timer.

**The countdown is deadline-based, and nothing ticks.** `TimerController` stores the monotonic
instant a phase ends (`ElapsedClock`, backed by `SystemClock.elapsedRealtime()`) and derives the
remaining time from the clock on demand. Phase boundaries are scheduled with
`AlarmManager.setAlarmClock` via the `DeadlineScheduler` abstraction, and the visible countdown in
the notification is drawn by the platform chronometer (`setUsesChronometer` +
`setChronometerCountDown`). Between boundaries the app runs no code at all.

The original implementation decremented a counter once per `delay(1000)`, so scheduling jitter
accumulated into permanent drift and the timer stalled entirely while the process was frozen.

`setAlarmClock` is used rather than `setExactAndAllowWhileIdle` because it is exempt from Doze
deferral and exact-alarm rate limits. Note that **this does not create an entry in the user's clock
app** — that would require sending `AlarmClock.ACTION_SET_ALARM`, which this app never does.

**The service is only in the foreground while the alarm is audible.** Since counting no longer
requires a live process, the service claims a foreground slot only for the duration of the alarm
sound, declared as `mediaPlayback`. This replaced `specialUse`, which requires a manual policy
review on Google Play.

**`AlarmSetting` is a sealed type** (`Off` / `Fixed(seconds)` / `Forever`). It replaced an `Int`
where `-1` meant "ring forever" — an encoding the preset editor could not even express, since it
clamped input to `1..300`.

**Input bounds live in `TimeRanges`** and are shared by every screen. The home screen and the
preset editor previously disagreed (0..999 vs 0..59 minutes), so a preset could not represent a
configuration the home screen accepted.

### Conventions

- Timer state belongs to the service layer. Do not reintroduce it into a composable's `remember`.
- Changes to phase progression go in `TimerEngine`, with a test.
- Do not reintroduce a per-second ticker in the service; the notification chronometer and
  `AlarmManager` cover display and wake-up respectively.
- Do not reintroduce the `alarmDuration = -1` sentinel.
- Colours come from `MaterialTheme.colorScheme`, not per-file `Color(0xFF…)` literals.
- Persisted data is decoded with `opt*` plus defaults and clamped; a single malformed record must
  never take down a screen. Bump `SCHEMA_VERSION` and add a migration when the shape changes.

## Testing

`./gradlew testDebugUnitTest` runs the whole suite (currently 60 tests) on the JVM; no device or
emulator is needed.

`TimerControllerTest` backs `ElapsedClock` with the coroutine test scheduler's virtual clock, so
`advanceTimeBy` moves the timer's notion of "now" in lockstep, and uses a fake `DeadlineScheduler`
so the test fires phase boundaries explicitly — exactly as `AlarmManager` would. Use `runCurrent()`
rather than `advanceUntilIdle()` when a coroutine is outstanding, so virtual time is not consumed
unintentionally.

Trust `app/build/test-results/**/*.xml` over console output when checking results.
