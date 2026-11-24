# Tasker plugin guide

Smart Sync's Tasker plugin lets Tasker, MacroDroid and Automate react to your watch and control it. No server, nothing
leaves the phone. It works for every watch the app supports, also watches that connect by their button: actions for
those wait for the next connection (`%gs_result` is then `queued`).

## Setup
1. Smart Sync: Settings, Plugins, Tasker: turn it on.
2. Tasker: Profile, Event (or State), Plugin, **Smart Sync watch event** (or **Smart Sync watch state**).
3. Tasker: Task, Action, Plugin, **Smart Sync watch action**.

Each screen asks for the watch: **Connected watch** or one watch by name.

## Events and states
| Item | Event | State | Level | Variables |
|---|---|---|---|---|
| Watch button pressed | yes | | | `%gs_time` |
| Watch connected | yes | yes | | `%gs_connection` (user, watch, background_read) |
| Watch disconnected | yes | | | |
| Step goal reached | yes | yes | the watch's goal | `%gs_steps`, `%gs_step_goal` |
| Heart rate above / below | yes | yes | bpm | `%gs_hr` |
| Watch battery below | yes | yes | % | `%gs_battery` |
| Workout stored | yes | | | `%gs_workout_sport`, `_minutes`, `_kcal`, `_hr_avg`, `_distance_m`, `_start` |
| Health read done | yes | | | `%gs_health_kinds` |

Every event also sets `%gs_watch` (name) and `%gs_watch_id`. A level event fires once when the value crosses the level,
not at every read.

## Actions
| Action | Fields |
|---|---|
| Send a notification | title, text, label (source line), type (message, calendar, email, call, generic) |
| Change a watch setting | one setting of the chosen watch (only what it has) and its value |
| Set an alarm or timer | the alarm and timer settings the watch has |
| Read the watch values now | |

Text fields and values may hold Tasker variables. Every action sets `%gs_result` (`ok`, `queued` or `failed`) and
`%gs_error` (empty, or the reason, for example `not_supported: step_goal is not on this watch`).

## Example profiles
1. **Find my phone**: Event Smart Sync watch event, Watch button pressed. Task: Media, Play Ringtone.
2. **Door bell on the wrist**: Event (your doorbell app's notification). Task: Smart Sync watch action, Send a
   notification, title `Door`, text `%evtprm2`.
3. **Low battery reminder**: State Smart Sync watch state, Watch battery below 15 %. Task: Notify `Charge %gs_watch
   (%gs_battery %)`; If `%gs_result ~ failed`: Flash `%gs_error`.
