# WristLink health ecosystem — build plan

This plan is for the engineer (or AI) who implements the next stage. It is self-contained: it
covers what to build, the data you can and cannot use (and why), the formulas and constants to
copy, the screen contents, the data schema, the companion-link protocol, and the build/install
story. Read [samsung-health-analysis.md](samsung-health-analysis.md) alongside it — that is the
reverse-engineering source this plan is derived from.

Goal: give a Samsung Galaxy Watch4 used with an iPhone the health experience Samsung Health gives
on a Samsung phone, built entirely on what a Wear OS app can collect — no Samsung Health, no
Android phone.

---

## 0. Context you must know before touching anything

### Repositories and apps

- **Watch app** — `watchbridge/`, package `com.watchbridge`, Kotlin + Jetpack Compose (Wear).
  Target: Galaxy Watch4 Classic SM-R890, One UI 8 Watch / Wear OS (Android 16), `minSdk 30`,
  `compileSdk 34`. Fork **WristLink** at `github.com/slymn08183/WristLink`.
- **iPhone app** — `ios/`, SwiftUI, bundle `io.github.slymn08183.wristlink`, iOS 17+. Built in
  GitHub Actions (XcodeGen → `xcodebuild` on `macos-15-intel`, unsigned, then ad-hoc `codesign`
  with entitlements), published to the `ios-latest` pre-release with a SideStore/AltStore
  `source.json`. No Mac is used locally.
- Code, commits, and docs in **English**. Keep the existing house style (comment density, naming).

### The companion link (already built — extend it, don't replace it)

BLE GATT server on the watch, `CompanionGattServer.kt`. The iPhone is already BLE-connected to the
watch (for notifications via ANCS), so the iPhone app finds the watch with
`retrieveConnectedPeripherals` and talks over that link.

- Service `57a1e1c0-0001-4b8e-9a1f-6f4b10000000`.
- **INFO** char `…0002` (read + notify): JSON with battery, link stats, pending sample count,
  `weatherAt`, `sleepSupported`, etc.
- **SYNC** char `…0003` (write + write-no-response + notify): **newline-framed JSON** messages,
  split to the MTU, reassembled on each side. This already carries:
  - `pull`/`batch`/`ack` — health sample sync (see §3).
  - `getSettings`/`setSetting`/`settings` — remote settings (`RemoteSettings.kt`).
  - `weather` — forecast from the iPhone (`WeatherStore.kt`).
  - `hello`/`fetch`/`image` — the iPhone announces `features:["images"]` and serves app icons and
    album art (`PhoneImages.kt` on the watch, `ImageFetcher.swift` on the iPhone).
  - `PROTOCOL_VERSION` is currently **5**. Bump it when you add message types and keep the watch
    tolerant of an older iPhone app and vice-versa (the code already does version checks).

### Existing health plumbing (the base of Phase 0)

- `health/HealthCollector.kt` — registers a Health Services `PassiveListenerConfig` for
  `STEPS_DAILY`, `DISTANCE_DAILY`, `HEART_RATE_BPM`; turns daily running totals into deltas; builds
  sleep sessions from `USER_ACTIVITY_ASLEEP`; exposes `platformSleepSupported`.
- `health/PassiveHealthService.kt` — the `PassiveListenerService` Health Services calls.
- `health/HealthStore.kt` — SQLite `health.db`, one table `samples(id, type, start, end, value)`,
  types `steps/distance/calories/hr/sleep`, `batch(afterId,max)` / `acknowledge(id)`, 14-day cap.
  **This is an outbox for the iPhone, not a history store.** Phase 0 adds a real local history
  (see §3) because the screens need to read days back, not just "what hasn't synced yet".
- iPhone `HealthSync.swift` — pulls batches, writes to HealthKit with
  `HKMetadataKeySyncIdentifier "wristlink:<type>:<start>"` for dedupe; acks after a successful save.

### Build / install / test (do it exactly this way)

- **Release build** needs the keystore env: `set -a && . ../keys/keystore.env && set +a` then
  `./gradlew --no-daemon assembleRelease`. The keystore lives at `../keys/` (outside git).
  `assembleDebug` works without it.
- **Install on the watch** over adb. The watch's wireless-debugging port changes on every
  reconnect; connect once to the shown port, then `adb tcpip 5555`, then use `:5555` until the
  watch restarts. There is a helper `scripts` dir and an `adbw.sh` in the session scratchpad.
  Ask the user for the current port when adb can't reach the watch — do not guess.
- **Grant the adb-only permissions** after install with `scripts/install.sh` / `install.bat`
  (deviceidle whitelist, `ACTIVITY_RECOGNITION`, `BODY_SENSORS(+_BACKGROUND)`,
  `health.READ_HEART_RATE`, `health.READ_HEALTH_DATA_IN_BACKGROUND`, DND access,
  `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, `RECEIVE_COMPLICATION_DATA`, install-packages).
- **Verify on device, every time.** Build → install → drive with adb (`am start`,
  `am broadcast ... DEBUG_SURFACE set-watchface`, `input`, `uiautomator dump`, `screencap`) →
  read the screenshot. Watch `logcat` for `FATAL`/`AndroidRuntime` and the app's own tags.
- The iPhone app builds on push via `.github/workflows/ios.yml`; watch the run, then it lands in
  `ios-latest` as Build N. There is no local Mac.

### Icon rendering

No Python locally. Rasterize SVG with headless Edge:
`msedge --headless=new --screenshot --default-background-color=00000000`. Vector drawables for
watch/tile/complication icons; preview PNGs via Edge.

---

## 1. Hard constraints — what you can and cannot collect

**Available through Wear OS Health Services (use these):**

- Passive: `STEPS_DAILY`, `DISTANCE_DAILY`, `CALORIES_DAILY`, `FLOORS_DAILY`,
  `ELEVATION_GAIN_DAILY`, `HEART_RATE_BPM`. Check `getCapabilitiesAsync()` first — this watch
  supports all of these.
- `UserActivityState`: `PASSIVE`, `EXERCISE`, `ASLEEP` (the watch's own sleep/wake detection).
- On-demand heart rate via `MeasureClient` (`HEART_RATE_BPM`) for a "measure now" screen.
- `ExerciseClient` for workouts: HR, `LOCATION` (GPS), `SPEED`, `PACE`, `DISTANCE`, `CALORIES`,
  `STEPS`, cadence, `ELEVATION_GAIN`, laps, auto-pause/resume, and `VO2_MAX` **only if** the
  capabilities list it (verify on this watch; Samsung's own VO2max comes from a private sensor).

**Blocked without root (do NOT spend time trying):** SpO2, sleep stages (REM/light/deep),
respiration rate, HRV/stress, skin temperature, BIA body composition, sensor-measured VO2max,
advanced running dynamics. Samsung reads these through its `SemSensorManager`
(`com.samsung.permission.SSENSOR`), whose binder `@EnforcePermission`s **every** call, including
just starting a sensor. `SSENSOR` is `signature|privileged`: only Samsung-signed or
`/system/priv-app` allowlisted apps get it. A device-owner/admin app cannot grant signature or
privileged permissions. A platform signature cannot be forged (cryptographic). The only thing that
changes this is root, which on Watch4 needs a hardware mod and risks a brick — treat it as out of
scope.

**Design rule:** build every screen so the blocked cards degrade gracefully — show them with a
"not available on this watch" state (like Samsung shows "Sleep stages not measured"), structured so
that if a data source ever appears they light up. Never block a whole screen on blocked data.

---

## 2. Delivery phases (suggested order)

Each phase is shippable on its own. Build, install, verify on the real watch/iPhone before moving on.

- **Phase 0 — Data foundation.** Local history store on the watch; expand collection; body profile;
  derived metrics (resting HR, active minutes); extend the sync protocol and HealthKit writes.
- **Phase 1 — Watch health screens + tiles.** Today (activity rings), Heart rate, Sleep.
- **Phase 2 — Watch-face integration.** Live heart rate and today's activity on the two faces and
  their complications (fixes the stale-HR problem for good).
- **Phase 3 — Workouts.** ExerciseClient workout app: types, live screens, laps, zones, auto-pause,
  summary; sync workouts (with GPS route) to the iPhone → Apple Health + Strava.
- **Phase 4 — iPhone app.** Samsung-phone-style home cards, trends, sleep and workout detail with
  maps and charts, weekly report.
- **Phase 5 — Coaching.** Inactivity alerts, HR alerts, goal nudges, streaks, badges, energy score.

---

## 3. Phase 0 — data foundation

### 3.1 Local history on the watch

`HealthStore` today is only an outbox (rows deleted on ack). The screens need to read history.
Add a **history database** (new `HealthHistory`/SQLite, or extend `HealthStore` with a second set
of tables and stop deleting on ack — keep a separate `synced_up_to` cursor instead of deleting).

Suggested tables (all times epoch-ms, local day = `YYYYMMDD` int):

- `hr_sample(t, bpm)` — every passive HR sample; also used for hourly min/max and resting HR.
- `activity_hour(day, hour, steps, distance_m, active_cal, floors, move_minutes, exercise_minutes,
  raised_hr_minutes)` — one row per hour, upserted from passive deltas.
- `sleep_session(start, end, …)` plus `sleep_gap(session_start, gap_start, gap_end)` for awake
  stretches within a sleep (derived from ASLEEP→awake transitions and/or step bursts at night).
- `workout(id, type, start, end, …summary fields…)` and `workout_track(workout_id, t, lat, lon,
  alt, hr, speed, …)` and `workout_lap(workout_id, n, …)` — Phase 3.
- `profile` (single row): sex, birth year, height cm, weight kg, max-HR override, resting-HR
  override, goal overrides. Default goals from §6.

Keep ~60–90 days; prune older. The outbox-to-iPhone logic stays, but reads its rows from history
with a `synced_up_to` id instead of deleting.

### 3.2 Expand collection

- Add `CALORIES_DAILY`, `FLOORS_DAILY` to the passive config (already partly scaffolded; calories
  was excluded — re-include it). Keep HR passive.
- Write hourly aggregates into `activity_hour` as deltas arrive (you already have the daily-delta
  logic in `HealthCollector`; bin it per hour).
- **Active minutes** (Health Services gives no "active time" — define it): a minute counts as
  *move* when cadence/steps in it exceed a small threshold; counts as *raised-HR* when HR ≥ zone 2
  lower bound (see §6 HR zones). Aggregate into `activity_hour`.
- **Active hour**: an hour with ≥20 steps or ≥1 exercise minute (Samsung's rule).
- **Resting HR** (Samsung reads it from the sensor; we derive it): the low percentile (≈5th) of HR
  samples while still (no steps in the surrounding minutes) over the day, or the minimum of a
  rolling night window. Store a daily resting HR and a 7-day trend.

### 3.3 Body profile

Add a watch settings screen and mirror it to the iPhone (via `RemoteSettings`/settings messages):
sex, birth year, height, weight. Needed for BMR, HR resting ranges and HR zones. Also expose goal
overrides. Default goals in §6.

### 3.4 Sync protocol + HealthKit

- Extend the sample stream: new sample `type`s for `floors`, `active_calories` (vs total), and the
  richer **sleep** payload (session with awake gaps) and **workouts** (Phase 3). Keep the existing
  `pull/batch/ack` framing; add fields, don't break old ones.
- iPhone `HealthSync.swift`: map new types to HealthKit (`HKQuantityType` flights climbed, active
  energy; `HKCategoryType` sleep analysis with `asleepUnspecified` + `awake` for gaps;
  `HKWorkout` with route via `HKWorkoutRouteBuilder` for Phase 3). Keep the sync-identifier dedupe.
- The watch should also keep its own copy for its screens (it no longer depends on the iPhone
  having pulled the data).

---

## 4. Phase 1 — watch health screens and tiles

Match Samsung's layout and labels (strings cited in the analysis doc). Wear Compose, same
`WatchBridgeScaffold`/`ScalingLazyColumn` patterns already in `ui/screens/`.

### 4.1 Today (daily activity)

- **Three rings** with selectable metric each (persist choice):
  - Move: Steps (default) / Floors / Active hours.
  - Time: Active time (default) / Exercise time / Raised-HR time.
  - Energy: Active calories (default) / Exercise calories.
- Center shows the primary ring's "x / y" (e.g. "4,312 / 6,000 steps").
- Below: "Activity by time of day" hourly bar chart; total burned calories (BMR+active, §6);
  distance while active; weekly "goals met" dots; streak count.
- Goal-progress nudges by time of day (Phase 5).

### 4.2 Heart rate

- **Latest reading** card (last passive sample + "measure now" using `MeasureClient`).
- **Today's HR** chart: 24 hourly **min/max bars**, x-labels 0/6/12/18/24, with a **resting-range
  band** (age/sex table, §6) and the day's **resting HR** line. Y-axis default top 120, grows to
  220. Verdict text "within / higher / lower than your resting range".
- Measurement mode setting: continuous / every 10 min while still / manual.
- Tags on a manual reading (General, Resting, Before/After exercise, …) — optional, nice-to-have.

### 4.3 Sleep

- Header: total sleep, bedtime, wake-up, and **our own sleep score** (see §6 — clearly our metric,
  not Samsung's; no fake stages).
- Time breakdown: time in bed, latency (if derivable), asleep, awake.
- **Movement/awake chart** across the night (from gaps + step bursts). No fake REM/deep.
- "Sleep stages not measured on this watch" note where Samsung would show the hypnogram.
- Sleeping HR (avg + min) from `hr_sample` during the session.
- Weekly consistency ("x of 7 days within your sleep goal"), day-to-day regularity (overlap with
  the previous night), and the **sleep animal** (3 axes, §6).

### 4.4 Tiles

Reuse the existing tile scaffolding (`tile/`). Add: Activity (rings), Heart rate, Sleep, and a
summary tile with a chosen list. Complications push updates (period 0).

---

## 5. Phase 2 — watch-face integration

Both faces (`watchface/DashboardWatchFaceService`, `WristLinkWatchFaceService`) already read
weather from `WeatherStore` directly. Do the same for **live heart rate**: read the latest sample
from the history store (or a small in-memory `MutableStateFlow` the collector updates) and draw it,
instead of relying on Samsung Health's complication (that is what froze at "89"). Offer a
WristLink heart-rate complication data source (our own) so any watch face — including Samsung's —
can show our live HR. Same for steps/active ring. Keep the burn-in rules in `Ambient.kt`.

---

## 6. Formulas and constants (copy these exactly)

**Goals (defaults):** steps 6000; active time 90 min; active calories 500 (male) / 300 (female);
exercise time 30 min; raised-HR time 30 min; floors 10; active hours 8; exercise calories
300/200. All overridable in the profile.

**Active hour:** ≥20 steps in the hour, or ≥1 minute of exercise.

**BMR (revised Harris–Benedict), prorated by elapsed fraction of the day:**
- male: `13.397·kg + 4.799·cm + 88.362 − 5.677·age`
- female: `9.247·kg + 3.098·cm + 447.593 − 4.33·age`
- Daily total calories = BMR + active (from Health Services) + food (we have none → omit or 0).

**Resting HR (derived):** lowest sustained HR while still/asleep over the day; keep a 90-day low and
a daily value.

**Resting range by age/sex (bpm, male / female)** — for the HR chart band and the reading verdict:

| Age | Male | Female |
|---|---|---|
| 6–8 | 76–92 | 79–94 |
| 9–11 | 70–86 | 76–91 |
| 12–15 | 66–83 | 70–87 |
| 16–19 | 61–78 | 69–85 |
| 20–39 | 61–76 | 66–82 |
| 40–59 | 61–77 | 64–79 |
| 60–79 | 60–75 | 64–78 |
| 80+ | 61–78 | 64–77 |

**HR alerts:** high 100–150 bpm (default 120), low 40–60 bpm (default 40), triggered after
**10 minutes at rest** (HR beyond the threshold while steps ≈ 0).

**Max HR:** default `220 − age`; let the user override; optionally raise it to the highest HR ever
seen in a workout.

**HR zones:**
- Max-HR mode (5 zones, % of max): Z1 50–60, Z2 61–70, Z3 71–80, Z4 81–90, Z5 91–100. Labels:
  Low intensity / Weight control / Aerobic / Anaerobic / Maximum. "Raised-HR" = Z2 lower bound and up.
- HRR (Karvonen), R = maxHR − restingHR: zone ends at rest+0.3R, +0.4R, +0.6R, +0.9R, max.

**Sleep score (our own, no stages — be honest it's ours):** combine, weighted, and band it
85/75/60 (Excellent/Good/Fair/Attention):
- Duration vs goal (target 7–8 h adult; penalize <6 h and >9 h).
- Efficiency = asleep / time-in-bed; good at ≥90%.
- Regularity = overlap of this sleep's window with the previous night's.
- Timing = bedtime vs the user's goal window.
Requires ≥1 h of sleep overlapping the goal by ≥1 min to show a score (Samsung's rule). Naps add a
few points, capped.

**Sleep animal (3 axes, no stages needed):** sleep drive (duration + efficiency ok?), awake rate
(awake < ~10%?), regularity (overlap ok?). Map:

| Animal | Problems |
|---|---|
| Lion | none |
| Penguin | wakes |
| Mole | irregular |
| Walrus | short |
| Hedgehog | wakes + irregular |
| Deer | short + wakes |
| Alligator | short + irregular |
| Shark | all three |

**Inactivity alert:** after 50 minutes with no movement; default window 09:00–17:00 on work days;
never during DND/theater; "not now" snoozes 50 min.

**VO2max level table (if the watch exposes VO2_MAX):** by sex and age decade. Example (male, 30s)
cutoffs: Very poor <29.8, Poor <37.0, Fair <43.4, Good <50.0, Excellent <58.8, else Superior.
(Full table in the analysis doc; recover more rows from the decompile if needed.)

**Training load (Phase 5, optional):** acute:chronic ratio over HR-based load; <0.8 undertraining,
<1.5 balanced, else overtraining; needs 7 days baseline.

---

## 7. Phase 3 — workouts

### 7.1 Workout app on the watch

- Use `ExerciseClient`. Support the common types that map to Health Services exercise types: run,
  walk, hike, bike (outdoor/indoor), treadmill, elliptical, rowing, and "other". Start with run /
  walk / bike / hike; add more after.
- **Live screens** (swipe/rotary between): primary data screen (duration, distance, pace, HR), an
  HR-zone screen, a lap screen, and a map screen (GPS). Configurable slots if time allows.
- Controls: pause/resume (auto-pause toggle via `ExerciseConfig.setIsAutoPauseAndResumeEnabled`),
  lap (double-press Back), finish. Voice/vibration cues every N km/min and at 50/90/100% of a
  target (time/distance/calorie target optional for v1).
- Discard a workout with no distance and <10 s (Samsung's rule).
- Metrics to record per second into `workout_track`: location, HR, speed/pace, distance, cadence,
  elevation. Compute laps.

### 7.2 Post-workout summary (watch)

Duration, distance, calories, avg/max HR, avg/max pace & speed, elevation gain, cadence, steps;
HR chart; HR-zone breakdown; pace chart; splits per km (pace/HR/elevation); route map; recovery HR
(HR drop in the 1–2 min after stop); personal bests; VO2max card if available.

### 7.3 Sync workouts out

- Send the workout (summary + track) to the iPhone over the SYNC channel (new message type;
  chunk the track like images are chunked).
- iPhone: write an `HKWorkout` with an `HKWorkoutRouteBuilder` (GPS) and associated HR/energy
  samples into Apple Health.
- **Strava:** our own integration, independent of Samsung. Register a Strava API application
  (client id/secret, redirect URI). The iPhone app runs the OAuth flow (user taps "Connect
  Strava", authorizes in Safari/ASWebAuthenticationSession, we store the token in the Keychain,
  refresh as needed) and uploads finished activities via Strava's upload API (GPX/TCX built from
  the track, or the activities endpoint). This is a normal third-party Strava integration — do not
  hook into or imitate Samsung's linking flow. Keep client secret out of the repo.

---

## 8. Phase 4 — iPhone app

SwiftUI, extend the existing tabbed app (`ios/WristLink/ContentView.swift`). Lay it out like
Samsung Health on a phone:

- **Home / Today:** reorderable cards (steps/activity first, then heart rate, sleep, recent
  workout, energy score). Pull data from HealthKit (what the watch synced) and from the live link.
- **Activity:** rings, hourly chart, weekly/monthly trends, streaks.
- **Heart rate:** today's min/max chart, resting-HR trend, alerts history.
- **Sleep detail:** score, time breakdown, movement/awake chart, sleeping HR, weekly consistency,
  the sleep animal; "stages not available on this watch" where Samsung shows the hypnogram.
- **Workout detail:** route map (MapKit), pace/HR/elevation charts (Swift Charts), splits, HR
  zones, recovery HR, PBs.
- **Weekly report:** energy score, avg sleep, avg wake time, total workout time, avg steps/
  distance/calories.
- Keep the existing Watch/Settings/Health tabs and the remote-settings + weather + images features.

---

## 9. Phase 5 — coaching

- Inactivity alert (§6 timing), as a watch notification with a "stretch"/"not now" action.
- HR alerts (§6) as notifications.
- Goal nudges by time of day ("halfway there" before 2pm, "almost there", "goal reached").
- Streaks and badges: step days in a row, single-day step milestones (10k/20k/30k/40k), workout
  firsts and cumulative distance, sleep streaks, score badges.
- **Energy score** (0–100, bands 85/75/60) from the factors we *can* compute: sleep time, sleep
  time consistency, bed/wake midpoint consistency, regularity, timing, yesterday's activity,
  activity consistency, sleeping HR vs the 7-day average. **Omit the HRV factor** (blocked) and
  renormalize the weights over the available factors; say in the UI it's a reduced score.

---

## 10. What to leave out (blocked — see §1)

Sleep stages and anything built on them (Samsung's real sleep score, cycles, physical/mental
recovery), SpO2, respiration rate, skin temperature, HRV/stress, BIA body composition,
sensor-VO2max, advanced running dynamics, sweat loss, snoring. Put a clear "not available on this
watch" state where these would appear; wire the data path so they can be filled later if a source
ever opens (e.g. a future root or a Samsung SDK partnership).

---

## 11. Cross-cutting reminders

- Battery: keep the passive listener; don't add high-frequency polling. Workouts use `ExerciseClient`
  (batched) not raw sensors. The link is already at a low-power interval; only workouts/transfers
  raise it.
- Everything that shows on a screen must also have a graceful empty/collecting/unavailable state.
- Match the existing code style and the companion-protocol versioning discipline.
- Verify on the real watch and the real iPhone after every phase (build → install → drive → screenshot
  → logcat). The iPhone side ships through CI to `ios-latest`.
- Reverse-engineered Samsung sources are reference only and are not committed. This repo keeps the
  derived facts (this doc + the analysis doc), not Samsung code.
