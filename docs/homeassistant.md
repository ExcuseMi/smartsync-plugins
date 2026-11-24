# Home Assistant plugin

Each watch becomes a device in Home Assistant: battery, steps, heart rate, sleep and workouts as sensors, the watch's
settings as controls, notifications to the watch, the watch button as a trigger, and hourly history in Home
Assistant's long-term statistics. It uses MQTT discovery. Full reference: [homeassistant/README.md](../plugins/homeassistant/README.md).

## Setup
1. In Home Assistant: an MQTT broker (the Mosquitto add-on, or Mosquitto next to Home Assistant) and the
   [MQTT integration](https://www.home-assistant.io/integrations/mqtt/).
2. In Home Assistant: Profile, Security, "Long-lived access tokens": create one as an administrator.
3. In the app: Settings, Plugins, Home Assistant: turn it on, enter the address you open Home Assistant with and the
   token, keep "Through your Home Assistant", Save.
4. In Home Assistant: Settings, Devices, MQTT: one device per watch.

## Options
| Option | What it does |
|---|---|
| Share health data with Home Assistant | On by default. Off removes the health and workout entities and sends no health value or history. |
| Directly to an MQTT broker | For a broker on your network; address and token are then for history only. |
| Create a dashboard for this watch | A dashboard the plugin keeps up to date and removes when turned off. Without a token: copy the YAML. |

## FAQ
| Question | Answer |
|---|---|
| Entities show "unavailable" | The phone has not reached Home Assistant for about an hour. Open the app; check the status line. |
| No history in statistics | History needs the address and an administrator's token. |
| I used the app's mobile app integration before | The sensors take the same entity ids once the old device is deleted, so dashboards and automations keep working. See "Moving from the mobile app integration" in the README. |
| Other MQTT tools | The topics work with openHAB, Node-RED and ioBroker too (README, "Topics without Home Assistant"). |
