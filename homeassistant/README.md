# Home Assistant plugin for G-Shock Smart Sync

Your watch in Home Assistant, with live values, real controls and history.

Adds each watch as a device in Home Assistant: battery, steps, heart rate, sleep, workouts and connection as sensors;
the watch face, auto light, heart rate mode, alarms and bedtime as controls you can change from a dashboard or an
automation; notifications to the watch; the watch button as a trigger; history in Home Assistant's own long-term
statistics, with the hours it missed filled from the watch. It uses MQTT discovery and works through your Home Assistant or a direct MQTT broker. As a bonus, the same
MQTT topics work with openHAB, Node-RED, ioBroker and anything else that speaks MQTT.

## Contents
1. [What you need](#what-you-need)
2. [Set up a broker](#set-up-a-broker) (Home Assistant OS, or Mosquitto in Docker)
3. [Add the MQTT integration](#add-the-mqtt-integration)
4. [The app side](#the-app-side)
5. [What appears in Home Assistant](#what-appears-in-home-assistant)
6. [Topics without Home Assistant](#topics-without-home-assistant)
7. [When the connection is gone](#when-the-connection-is-gone)
8. [Moving from the mobile app integration](#moving-from-the-mobile-app-integration)
9. [Building](#building)

## What you need
- Home Assistant with its [MQTT integration](https://www.home-assistant.io/integrations/mqtt/) and an MQTT broker.
- For the recommended connection (through Home Assistant) and for history: the address you open Home Assistant with
  and a long-lived access token of an **administrator** (Profile, Security tab, "Long-lived access tokens"; see
  [Home Assistant authentication](https://www.home-assistant.io/docs/authentication/#your-account-profile)). The
  plugin subscribes to its command topics with `mqtt/subscribe` and imports statistics, and both need an admin.

## Set up a broker

### Home Assistant OS or Supervised
Install the Mosquitto broker add-on from the add-on store and create a Home Assistant user for it (or a login in the
add-on's options), as described in the
[Mosquitto add-on documentation](https://github.com/home-assistant/addons/blob/master/mosquitto/DOCS.md).

### Home Assistant Container or Core
There is no add-on store: run Mosquitto next to Home Assistant, for example with Docker Compose and the
[eclipse-mosquitto image](https://hub.docker.com/_/eclipse-mosquitto).

`docker-compose.yml`:
```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    restart: unless-stopped
    ports:
      - "1883:1883"   # MQTT, keep it inside your network
      - "9001:9001"   # MQTT over websockets
    volumes:
      - ./mosquitto/config:/mosquitto/config
      - ./mosquitto/data:/mosquitto/data
      - ./mosquitto/log:/mosquitto/log
```

`mosquitto/config/mosquitto.conf` (all options: [mosquitto.conf](https://mosquitto.org/man/mosquitto-conf-5.html)):
```
persistence true
persistence_location /mosquitto/data/
log_dest stdout

allow_anonymous false
password_file /mosquitto/config/passwd

listener 1883

listener 9001
protocol websockets
```

Users and passwords ([mosquitto_passwd](https://mosquitto.org/man/mosquitto_passwd-1.html)); one for Home Assistant,
one for the app if it connects directly:
```sh
docker compose run --rm mosquitto mosquitto_passwd -c /mosquitto/config/passwd homeassistant
docker compose run --rm mosquitto mosquitto_passwd /mosquitto/config/passwd smartsync
sudo chown 1883:1883 mosquitto/config/passwd && sudo chmod 0600 mosquitto/config/passwd
docker compose up -d
```
The container runs as uid 1883: the password file must belong to it and be readable only by it (mode 0600), or
Mosquitto warns (later versions refuse such a file).

## Add the MQTT integration
Settings, Devices & services, Add integration, MQTT: broker host (the machine running Mosquitto), port 1883, the
`homeassistant` user and its password. See the [MQTT integration](https://www.home-assistant.io/integrations/mqtt/).
Discovery is on by default with the prefix `homeassistant`; the plugin uses the same.

## The app side
Settings, Plugins, Home Assistant. At the top: the Home Assistant address and the token. Then **How to connect**:

| Choice | How it works | When |
|---|---|---|
| Through your Home Assistant (recommended) | publishes with the `mqtt.publish` action and receives commands with `mqtt/subscribe`, over Home Assistant's [websocket API](https://developers.home-assistant.io/docs/api/websocket/) | everywhere Home Assistant is reachable (at home, or outside through your usual [remote access](https://www.home-assistant.io/docs/configuration/remote/)); the broker stays inside your network |
| Directly to an MQTT broker | the app is an MQTT client of the broker: `tcp://`, `ssl://`, `ws://` or `wss://` | a LAN-only setup, or another system than Home Assistant |

Direct, inside the home: broker address, port 1883, user `smartsync` and its password. Direct from outside the home:
put the broker's websockets listener (port 9001 above, `protocol websockets` in
[mosquitto.conf](https://mosquitto.org/man/mosquitto-conf-5.html)) behind a reverse proxy or a tunnel with TLS and use
`wss://your.domain/` as the address. **Never expose port 1883 to the internet without TLS and a password.**

In direct mode the Home Assistant address and token are optional; with them the plugin also imports history.
Passwords and tokens stay in the app's plugin storage and never appear in a log line.

## What appears in Home Assistant
One device per watch (name, model, firmware), with entities only for what that watch has (an ocean watch gets no
health sensors; the watch face options are the ones of that model). Discovery:
[MQTT discovery](https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery).

| Kind | Entities |
|---|---|
| sensor | battery, temperature, steps today, step goal, energy, distance, active minutes, heart rate (now, resting, min, max), sleep (score, total, deep, light, REM, awake), blood oxygen, last workout (sport, start, duration, heart rate), home city, timer, firmware, phone battery, last sync, last command |
| binary_sensor | connected |
| select, switch, number, text | every setting the watch offers (choice, on/off, number, time as HH:MM) |
| notify | send a notification to the watch ([MQTT notify](https://www.home-assistant.io/integrations/notify.mqtt/)): title and message |
| event + device trigger | the watch button ([MQTT event](https://www.home-assistant.io/integrations/event.mqtt/), [device trigger](https://www.home-assistant.io/integrations/device_trigger.mqtt/)) |
| button | read the values now |

History: the sensors have a state class (steps today `total_increasing`, heart rate, battery and temperature
`measurement`), so Home Assistant keeps their long-term statistics itself; see them in a
[statistics graph card](https://www.home-assistant.io/dashboards/statistics-graph/). With the Home Assistant address and
token set, the plugin also fills the hours Home Assistant has no statistics for (phone off, watch out of range, Home
Assistant down): it asks Home Assistant what it has for the last 7 days and imports only the missing hours, from the
watch's history, into the same statistic of the sensor (steps per hour, heart rate mean, min and max). Only gaps
between two recorded hours are filled, never above the next recorded sum, so Home Assistant's own numbers stay as
they are. Without the token, history is what Home Assistant recorded live.

A notification with more fields (an app label and a type: message, calendar, email, call, generic) can be published
to `smartsync/<watch>/set/notify` as JSON: `{"title": "Door", "message": "Front door opened", "app": "Alarm", "type": "message"}`.

## Topics without Home Assistant
`<watch>` is the watch's id, for example `gshock-aabbccddeeff`. Values are retained.

| Topic | Payload |
|---|---|
| `smartsync/<watch>/state/<value>` | the value (`80`, `ON`, `2026-11-24T09:00:00+01:00`) |
| `smartsync/<watch>/state/setting/<key>` | the setting's value |
| `smartsync/<watch>/set/setting/<key>` | write a setting (checked against the watch's allowed values) |
| `smartsync/<watch>/set/notify` | a notification: plain text (first line the title) or JSON as above |
| `smartsync/<watch>/set/refresh` | read the values now |
| `smartsync/<watch>/result` | after each command: `{"target":"setting/watch_face","value":"Tide","status":"done"}`; status `done`, `queued` (sent at the next connection), `refused` (with a reason) or `failed` (`not_connected`, `not_supported`, ...) |
| `smartsync/<watch>/event/button` | `{"event_type":"press"}` per press, not retained |
| `smartsync/<watch>/history/steps_hourly/<date>` | `[{"t":"2026-11-23T10:00+01:00","v":812},...]` |
| `smartsync/<watch>/history/heart_rate_hourly/<date>` | `[{"t":"2026-11-23T10:00+01:00","mean":72.5,"min":60,"max":101},...]` |
| `smartsync/<watch>/history/workout/<id>` | one workout with its start and end times |
| `smartsync/<watch>/availability`, `smartsync/bridge/availability` | `online` or `offline` |

History topics keep the last 7 days; a day is published again only when it changed.

## When the connection is gone
- Values and states that could not be sent (no network, Home Assistant or the broker down) are sent when the
  connection is back: the plugin publishes every watch's current values again after each reconnection, and with the
  token the gap check fills the hours Home Assistant missed. Button presses are kept (the last 50) and sent then too.
- Notifications and commands are live: they need the connection at that moment. A command sent while the app was
  away is lost; after the reconnection the controls show the watch's real values again. A refused or failed change
  shows the real value at once.
- Direct mode: the broker publishes `offline` for the plugin when the phone drops (last will). Through Home Assistant
  there is no last will: the plugin sends its values every 30 minutes and the sensors become unavailable after 65
  minutes without them. A watch that keeps its link up shows unavailable while disconnected; a watch that connects by
  its button keeps its last values.
- Home Assistant restarted: it says so on `homeassistant/status` and the plugin publishes discovery and states again.

## Dashboard (optional)
"Create a dashboard for <watch>" in the plugin settings makes a dashboard at `/smartsync-<watch id>` with what that
watch has: steps against the goal, heart rate now and today, last night, the controls (face, slot, light, heart rate
mode, smart alarm, bedtime), battery and connection, the last workout. The plugin updates it when the watch's entities
change and removes it when the option is turned off or the watch is forgotten. It only touches a dashboard it made
(a marker in the dashboard's config); one made by you at that address is left alone. Needs the Home Assistant
address and an administrator's token; without one, "Copy the YAML" gives the same dashboard for the raw
configuration editor.

## Moving from the mobile app integration
The sensors ask for the entity ids the app's mobile app integration used (`default_entity_id`, for example
`sensor.casio_gbx_h5600_steps_today`, `binary_sensor.casio_gbx_h5600_watch_connected`), with the same unit and state
class, so dashboards, automations and long-term statistics carry on. Home Assistant gives an id only when the entity
is first discovered, and adds `_2` while the old one still exists, so the order matters:
1. Turn off the in-app Home Assistant integration and delete its mobile app device in Home Assistant.
2. Turn on this plugin. Its entities take the old ids.

Not carried over: the old read-only settings sensors (watch face, bedtime, smart alarm, heart rate measurement,
custom face slot, watch settings) are now controls (select, switch, number, text) with new ids; the raw diagnostic
sensors and the old device tracker and notify entities have no counterpart (use the notify entity of this device).

## Building
A module of [smartsync-plugins](../README.md), built against its `plugin-api` module; CI builds and tests every push.
JitPack after a tag `homeassistant-v<version>`: `com.github.ExcuseMi.smartsync-plugins:homeassistant:homeassistant-v<version>` (no tag yet).
