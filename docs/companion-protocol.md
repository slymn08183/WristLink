# Companion protocol, version 6 (health ecosystem)

The link itself is unchanged (see `CompanionGattServer.kt`): newline-framed JSON on the SYNC
characteristic, split to the MTU both ways. Version 6 adds the messages below. A version-5
iPhone app keeps working: it never sends the new requests and skips sample types it doesn't know.
The iPhone app checks `protocol` in INFO before using anything here.

## INFO additions

- `protocol`: 6
- `workoutsPending`: workouts recorded on the watch that the iPhone hasn't confirmed yet.
- `hr`: `{"bpm": 72, "t": <ms>}` — the latest heart rate, or absent.

## Sample stream (`pull` / `batch` / `ack`)

New `t` values in `items`, on top of `steps`, `distance`, `hr`, `sleep`:

| `t` | meaning | HealthKit |
|---|---|---|
| `floors` | floors climbed in [s, e] | `flightsClimbed` |
| `activeCalories` | active energy, kcal, in [s, e] | `activeEnergyBurned` |
| `awake` | awake stretch inside a night, [s, e] | sleepAnalysis `.awake` |
| `restingHr` | the day's resting HR (bpm), s = e = local midnight of that day | `restingHeartRate` |

`sleep` samples are still the asleep stretches (`asleepUnspecified`).

## Summary (computed on the watch)

The watch computes every derived number (rings, resting HR, sleep score, animal, energy score) so
the iPhone never re-implements the formulas.

Request: `{"op":"summary","days":7}` (1–14).

Reply:

```json
{"op":"summary",
 "at": 1762160000000,
 "profile": {"configured":true,"sex":"male","birthYear":1995,"heightCm":175,"weightKg":75,
             "maxHr":189,"maxHrOverride":null,"stepGoal":6000,"activeMinutesGoal":90,
             "activeCaloriesGoal":500,"exerciseMinutesGoal":30,"sleepGoalMinutes":480,
             "bedtimeMinutes":1380,"hrAlerts":true,"hrHigh":120,"hrLow":40,
             "inactivityAlert":true,"goalNudges":true},
 "restingRange": [61, 76],
 "days": [
   {"day":"2026-11-03", "start": <local midnight ms>,
    "steps":4312, "distance":3120.5, "floors":4, "activeCalories":210, "totalCalories":1650,
    "activeMinutes":38, "exerciseMinutes":0, "raisedHrMinutes":12, "activeHours":6,
    "restingHr":58, "hrMin":52, "hrMax":131,
    "stepGoalMet": false, "allGoalsMet": false,
    "hourlySteps":[0,0,...24 values], "hourlyHrMin":[null,...24], "hourlyHrMax":[null,...24]}
 ],
 "sleep": [
   {"night":"2026-11-03", "start":<ms>, "end":<ms>, "inBedMin":465, "asleepMin":432,
    "awakeMin":33, "efficiency":0.93, "score":82, "band":"Good",
    "avgHr":54, "minHr":49, "gaps":[[<ms>,<ms>]], "movement":[0,3,0,12,...]}
 ],
 "animal": {"name":"Penguin","short":false,"wakes":true,"irregular":false} ,
 "energy": {"score":78, "band":"Good", "reduced":true},
 "streak": 3}
```

- `days` and `sleep` are oldest first; the last entry is today / last night.
- `movement` is steps per 10-minute bucket from `start`.
- `score`/`band` are null when the night is too short to score (< 1 h asleep).
- `animal` is null until there are 3 nights; `energy` is null without last night's sleep.
- `band`: "Excellent" ≥ 85, "Good" ≥ 75, "Fair" ≥ 60, else "Attention".

## Workouts

Request the oldest unconfirmed workout: `{"op":"nextWorkout"}`.

Reply when there is none: `{"op":"workout","none":true}`. Otherwise:

```json
{"op":"workout", "id":12, "type":"run", "start":<ms>, "end":<ms>, "activeMs":1834000,
 "distance":5012.3, "calories":402, "steps":5320, "avgHr":151, "maxHr":178,
 "elevationGain":34, "summary":{...free-form extra fields...},
 "laps":[{"n":1,"s":<ms>,"e":<ms>,"d":1000,"hr":148}],
 "track":[[<ms>, lat, lon, alt, hr, speed], ...]}
```

- `type`: run, walk, hike, bike, bike_indoor, treadmill, elliptical, rowing, other.
- `track` entries: any of lat/lon/alt/hr/speed may be null (indoor workouts have no position).
  The watch thins the track to about one point every 3 s.

Confirm once it is in Apple Health: `{"op":"workoutAck","id":12}`; the watch marks it synced and
the iPhone asks for the next one.

## Settings additions

`settings.values.profile` carries the profile object above. The iPhone changes a field with the
existing `setSetting`, key `profile.<field>` (e.g. `profile.weightKg`, value 78).
