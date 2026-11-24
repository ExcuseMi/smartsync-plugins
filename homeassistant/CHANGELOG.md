# Changelog

## 0.1.0
- First version: each watch as a Home Assistant device through MQTT discovery (sensors, settings as select, switch,
  number and text, a notify entity, the button as event and device trigger, a read-now button, the last command).
- Two ways to connect: through Home Assistant (mqtt.publish and mqtt/subscribe over its websocket API) or directly
  to a broker (tcp, ssl, ws, wss).
- Hourly steps and heart rate as long-term statistics, each hour once, with a gap check of the last 7 days.
- Plain topics for other systems: states, commands, results, button events, per-day history and workouts.
- After a reconnection or a Home Assistant restart everything is published again; button presses are kept meanwhile.
