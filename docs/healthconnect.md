# Health Connect plugin

Writes the watch's steps, heart rate, sleep, blood oxygen and workouts to Android Health Connect, so apps such as
Google Fit can show them. It only writes; it never reads Health Connect. Details: [healthconnect/README.md](../plugins/healthconnect/README.md).

## Setup
1. A watch with a health log (the GBX-H5600 family).
2. In the app: Settings, Plugins, Health Connect: turn it on: Health Connect's permission screen opens at once; allow the writes.
3. Read health data from the watch as usual.

## FAQ
| Question | Answer |
|---|---|
| Where do I see the data? | The Health Connect app: Data and access, then Steps, Heart rate, Sleep or Exercise, source G-Shock Smart Sync. |
| Will it write twice? | No. Each record has a stable id; a second read writes nothing, a changed value replaces the record. |
| Nothing is written | Check the permissions on the plugin's screen, and the App log for "Health Connect:" lines. |
| Workout distance? | Not written yet: its unit is not confirmed. |
