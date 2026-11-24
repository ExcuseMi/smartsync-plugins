# Smart Sync Tasker plugin

A plugin for the G-Shock Smart Sync app (plugin interface `plugin-api`). Tasker, MacroDroid and Automate get watch
events and states for their profiles and watch actions for their tasks. Guide: `docs/tasker.md`.

| Part | File |
|---|---|
| The intent contract (Locale with Tasker's events, variables and completion) | `Protocol.kt` |
| Configurations and their bundles | `Config.kt` |
| Host events to Tasker events, matching, variables | `Events.kt` |
| States | `States.kt` |
| Actions to host calls, settings offered per watch | `Actions.kt` |
| Receivers and edit screens | `Receivers.kt`, `EditScreens.kt`, `Bridge.kt` |

## Licence of the Tasker side
The Tasker plugin library (joaomgcd/TaskerPluginSample) is GPL-3.0, so it is not used: this module writes out the
documented intents itself (Apache-2.0 like the repository).

## Security
The edit screens and receivers are exported, as the contract needs: any app on the phone can send an action. Actions
run only while the plugin is on, and only through the host's checks (declared controls, the watch's own settings).
