# smartsync-plugins

Plugins for [G-Shock Smart Sync](https://github.com/ExcuseMi/CasioGShockSmartSync), and the interface they are built on.

A plugin is an Android library the app loads through `java.util.ServiceLoader`. It sees the app only through
`plugin-api`: the paired watches and what they can do, the values and history the app stored, notifications and
setting changes through the app's watch queue, its own storage, and events (connected, values read, health read,
workout stored, button pressed, ...). It declares what it reads, controls and sends where (`PluginAccess`); the app
shows that to the person and keeps the plugin to it.

| Module | What it is | Version |
|---|---|---|
| [`plugin-api`](plugin-api/) | The interface between the app and its plugins | 0.1.0 (`plugin-api-v0.1.0`) |
| [`homeassistant`](homeassistant/) | The watch in Home Assistant through MQTT discovery: values, controls, history | 0.1.0 (`homeassistant-v0.1.0`) |
| [`healthconnect`](healthconnect/) | Steps, heart rate, sleep and workouts to Android Health Connect | 0.1.0 (`healthconnect-v0.1.0`) |
| [`trmnl`](trmnl/) | The watch's values to a TRMNL private plugin (webhook) | 0.1.0 (`trmnl-v0.1.0`) |

## Writing a plugin
1. A new module `<name>/` (Android library), with `compileOnly(project(':plugin-api'))` and
   `testImplementation(project(':plugin-api'))`, included in `settings.gradle`.
2. A class implementing `WatchPlugin` with a public constructor without arguments, named in
   `src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin`.
3. Declare `access` (what it reads, controls, sends and keeps) and, in `activate`, its needs (`PluginHost.declareNeeds`).
4. Unit tests against a fake `PluginHost`; see any of the plugins here. Rules of the interface: `plugin-api/README.md`.

## Versions and coordinates
Each module has its own version (its `build.gradle`) and `CHANGELOG.md`, and is tagged on its own:
`plugin-api-v0.1.0`, `homeassistant-v0.1.0`, ... JitPack: `com.github.ExcuseMi.smartsync-plugins:<module>:<tag>`.

## Build
CI (`.github/workflows/ci.yml`) builds, lints and tests every module on each push and pull request. Locally:
`./gradlew testDebugUnitTest` (JDK 21, Android SDK).

## License
Apache License 2.0 (`LICENSE`).
