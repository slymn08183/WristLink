# Samsung Health, taken apart: what WristLink can take from it

WristLink's watch can't use Samsung Health: without a Samsung/Android phone the app won't open.
To build our own equivalent we decompiled Samsung Health for the watch (as installed on a
Galaxy Watch4, One UI 8 Watch) and for Android phones (7.1.3 beta) with jadx, and mapped what
it shows, what it computes and which data it needs. This page is the result and the plan.

Only facts, formulas and constants are recorded here, no Samsung code. The decompiled sources
are not part of the repository.

## The data itself is closed

The watch app exports `HealthDataProvider` (`com.samsung.android.wear.shealth.healthdataprovider`)
behind a *normal* permission, which looks open, but every `call()` compares the caller's signing
certificate against Samsung's own and answers `no_permission` otherwise; `query()` always returns
nothing. Without root no other app can read Samsung Health's data. WristLink measures for itself.

## Samsung builds on the same platform we have

Much of Samsung Health on the watch sits on Wear OS **Health Services**, which WristLink can use too:

- Workouts: `ExerciseClient` with heart rate, location, pace, speed, elevation, VO2max where
  available, auto-pause/resume, laps.
- Sleep detection: the `USER_ACTIVITY_ASLEEP` state (what WristLink already uses for quiet mode).
- Passive steps, floors, heart rate in the background.

What Samsung adds on top comes from its private sensor hub and is out of reach: sleep stages,
SpO2, skin temperature, respiration, HRV/stress, body composition, running dynamics, AT/AnT.

## What each area shows and computes

### Daily activity

- Three rings with a selectable metric each. Defaults: **6,000 steps**, **90 min active time**,
  **500 / 300 kcal active calories** (male / female). Alternatives: 10 floors, 8 active hours,
  30 min exercise time, 30 min "raised HR" time.
- An **active hour** is an hour with at least 20 steps (or a minute of exercise).
- **Raised-HR time** is time with heart rate in zone 2 or higher.
- **Calories** = BMR + active + food effect. BMR is revised Harris–Benedict, prorated over the day:
  - male `13.397·kg + 4.799·cm + 88.362 − 5.677·age`
  - female `9.247·kg + 3.098·cm + 447.593 − 4.33·age`
- Screens: progress "x out of y", activity by time of day (hourly bars), total burned calories,
  distance while active, weekly "all goals met", monthly streak, step badges at 10k/20k/30k/40k.
- Nudges by time of day: "halfway there" before 2 pm, "almost there", goal reached.

### Heart rate

- **Today's HR**: 24 hourly min/max bars, with the day's resting HR and the resting range as a band.
- **Resting range** by age and sex (bpm; male / female): 20–39: 61–76 / 66–82,
  40–59: 61–77 / 64–79, 60–79: 60–75 / 64–78, 80+: 61–78 / 64–77 (children have their own rows).
  A reading is "within / higher / lower" than the range.
- **Resting HR**: the lowest HR while still or asleep; the phone keeps a 90-day history.
- **Alerts**: high (100–150, default 120) and low (40–60, default 40) after **10 minutes at rest**.
- Measurement modes: continuous, every 10 min while still, manual.

### Sleep

- Header: total sleep, bedtime, wake-up time, score.
- Score bands: **≥85 Excellent, 75–84 Good, 60–74 Fair, <60 Attention**.
- Score factors: total sleep time, sleep cycles, awakenings/restfulness, deep (physical recovery),
  REM (mental recovery), latency. The score itself is native code (`libsleepscore.so`) and needs
  stages; **without stages Samsung shows no score**, only duration, efficiency and movement.
- Typical stage shares: deep 10–20 %, light 40–60 %, REM 20–30 %, awake 0–9 %.
- Rules of thumb in its help texts: 7–8 h for adults (less score above 9 h), score drops at
  ≥10 % awake, restful at ≥90 % actually asleep.
- A score needs ≥1 h of sleep overlapping the sleep goal; naps add up to 5 points a day.
- **Sleep coaching animals** use three axes that need no stages: sleep drive (time and efficiency),
  awake rate, regularity. Lion = no problem, Penguin = wakes, Mole = irregular, Walrus = short,
  Hedgehog = wakes + irregular, Deer = short + wakes, Alligator = short + irregular, Shark = all.
- Also: weekly consistency ("x of 7 days"), day-to-day overlap (regularity), bedtime guidance,
  sleeping HR, and Samsung-only cards (SpO2, respiration, skin temperature, snoring).

### Workouts

- About 110 workout types; the common ones map one-to-one to Health Services exercise types.
- Live metrics: duration, distance, calories, current/average pace and speed, HR (current, avg,
  max, % of max), cadence, steps, elevation and gain, laps (pace, distance, time), intervals.
- Live screens: configurable data screens, HR-zone screen, lap info, map with track back.
  Lap by double-pressing Back. Voice/vibration cues every N km or minutes and at 50/90/100 % of a
  target. Auto-pause. Workouts under 10 s with no distance are not saved.
- **HR zones**: Low intensity / Weight control / Aerobic / Anaerobic / Maximum.
  - Max-HR mode: 50–60 / 61–70 / 71–80 / 81–90 / 91–100 % of max HR; max HR defaults to
    **220 − age**, later replaced by the highest measured.
  - HRR (Karvonen) mode with R = max − resting: zones end at rest + 0.3R, 0.4R, 0.6R, 0.9R, max.
- Summary: route map; HR, pace/speed, elevation and cadence charts; HR-zone breakdown; splits per
  km (pace, HR, elevation, steps); best lap; recovery HR; VO2max with a level table by age and sex
  (e.g. men in their 30s: <29.8 very poor … ≥58.8 superior); personal bests and badges
  (first run, 5K/10K/half/full, cumulative 100/200/500/1000 km).
- Training load (phone): acute/chronic ratio, <0.8 undertraining, <1.5 balanced, else overtraining.

### Inactivity, energy score, tiles

- **Inactivity alert** after **50 minutes** without moving, only 09:00–17:00 on work days by
  default, never on Do Not Disturb; "not now" snoozes another 50 minutes.
- **Energy score** (0–100, same bands as sleep): sleep time (7–9 h best), sleep time consistency,
  midpoint consistency, regularity, timing, yesterday's activity, activity consistency, sleeping
  HR vs the 7-day average (slightly lower is better), sleeping HRV (Samsung-only).
- **Tiles**: daily activity, steps, heart rate, sleep, exercise, summary with a chosen list.
  Complications push updates (no polling): steps as ranged/goal progress, HR and sleep as text.

## What WristLink can build

| Feature | Data it needs | WristLink |
|---|---|---|
| Steps, distance, floors, calories (total and active), hourly bars | passive Health Services | yes |
| Rings: steps, active minutes, active calories; active hours | steps, HR, our own active-minute rule | yes |
| Today's HR: hourly min/max, resting HR, resting range, high/low alerts | passive HR | yes |
| Live HR on the watch face | passive HR (or on-demand measurement) | yes |
| Sleep: time in bed, asleep, awakenings, efficiency, regularity, sleeping HR | asleep state, HR, steps | yes |
| Sleep "score" and animal type without stages | the three axes above | yes, our own, labelled as such |
| Sleep stages, SpO2, respiration, skin temperature, HRV, stress | Samsung sensor hub | no |
| Workouts with GPS: live screens, laps, zones, auto-pause, summary, charts | ExerciseClient | yes |
| VO2max | Health Services if the watch offers it | to check on the watch |
| Workouts to Apple Health (with route) and Strava | iPhone app | yes |
| Inactivity alert, goal nudges, streaks, badges, weekly report | all of the above | yes |
| Energy score | sleep + activity + sleeping HR (no HRV) | yes, without the HRV part |

## Plan

1. **Data layer on the watch.** Keep everything Health Services gives, at the resolution charts
   need: steps, distance, floors and calories per hour; every HR sample; sleep sessions with the
   awake gaps inside them; a body profile (age, sex, height, weight) for BMR, resting range and
   zones; derived resting HR. Same samples go to the iPhone and Apple Health.
2. **Watch app screens and tiles** in Samsung's layout: Today (rings), Heart rate (24 h chart),
   Sleep (last night and the week), plus tiles; the watch faces show our own live heart rate.
3. **Workouts**: a WristLink workout app on ExerciseClient (run, walk, cycle, hike, treadmill and
   others), live screens, laps, zones, auto-pause, summary; saved as a route and synced to the
   iPhone, Apple Health and Strava.
4. **iPhone app**: Samsung's phone layout: home cards, trends, sleep and workout detail with maps
   and charts, weekly report.
5. **Coaching**: inactivity alerts, HR alerts, goal nudges, streaks and badges, energy score.
