# Health Connect plugin for G-Shock Smart Sync

A plugin for [G-Shock Smart Sync](https://github.com/ExcuseMi/CasioGShockSmartSync) that writes the health data of
watches with a health log (the GBX-H5600 family today) to Android Health Connect, so apps such as Google Fit can show it.
It only writes; it never reads Health Connect.

It is built on the app's plugin interface (`plugin-api`) only: no app or watch library classes.

## What it writes

| Health Connect record | From | When |
|---|---|---|
| Steps | steps per hour | every hour with steps (a gap is never written as 0) |
| Heart rate | the day's heart rate samples | one record per day, updated as the day fills |
| Resting heart rate | the day's resting value | finished days, time 23:59:59 |
| Oxygen saturation | the day's blood oxygen | finished days, time 23:59:59 |
| Sleep session | fell asleep, woke up, stages | stages only when the app marks them proven |
| Exercise session | workout sport, start, end | after each workout read |
| Total calories burned | workout energy | only when its unit is proven |

Workout distance is not written yet (the plugin interface does not state its unit). Heart rate during a workout is in
the day's heart rate record, which Health Connect apps show inside the session.

Each record has a stable `clientRecordId` (`smartsync/<watch>/<kind>/<hour, day or workout>`). A second read of the
same data writes nothing; a changed value is written with the next `clientRecordVersion` and replaces the old record.

## Use

1. In the app: Settings, Health Connect, turn it on, then Allow (Health Connect's permission screen).
2. Read health data from the watch as usual. The App log shows one line per write, for example
   `Health Connect: wrote steps 12, heart rate 1`.
3. Check in the Health Connect app: Data and access, then Steps, Heart rate, Sleep or Exercise, source G-Shock Smart Sync.

## Build

A module of [smartsync-plugins](../README.md), built against its `plugin-api` module; CI builds and tests every push.
JitPack after a tag `healthconnect-v<version>`: `com.github.ExcuseMi.smartsync-plugins:healthconnect:healthconnect-v<version>` (no tag yet).

## License

Apache License 2.0, like the app.
