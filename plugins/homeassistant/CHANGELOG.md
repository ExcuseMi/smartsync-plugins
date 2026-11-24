# Changelog

## 0.1.1
- A re-paired watch (new Bluetooth address) leaves Home Assistant under its old id: the old device's topics are
  emptied and the watch shows up under the new id.
- A JSON notification may carry `app` and `type` in a `data` object, as the mobile app's notify action had them.

## 0.1.0
- First version: each watch as a Home Assistant device through MQTT discovery (sensors, settings as select, switch,
  number and text, a notify entity, the button as event and device trigger, a read-now button, the last command).
- Two ways to connect: through Home Assistant (mqtt.publish and mqtt/subscribe over its websocket API) or directly
  to a broker (tcp, ssl, ws, wss).
- History from Home Assistant's own statistics of the sensors; with a token, hours it missed are filled from the watch.
- Plain topics for other systems: states, commands, results, button events, per-day history and workouts.
- After a reconnection or a Home Assistant restart everything is published again; button presses are kept meanwhile.
- The entity ids of the app's mobile app integration, and an optional dashboard per watch that the plugin keeps up to date.
- "Share health data with Home Assistant" (on by default): off removes the health and workout entities and sends no
  health value, history or statistic.
- On plugin-api 0.1.0: settings from `PluginNeeds.settings`, time stamps by `ValueType` (`LOCAL_DATE_TIME` gets the
  phone's offset), the heartbeat and the daily gap check on `WatchEvent.Wake` instead of timers, reads every data
  kind but location and every control, details page metadata and a guide (`docs/homeassistant.md`).
