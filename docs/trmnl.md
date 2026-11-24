# TRMNL plugin

Pushes each watch's steps, heart rate, battery and more to a TRMNL private plugin (webhook), so a TRMNL e-paper display
shows the watch without Home Assistant. Details, limits and payload: [trmnl/README.md](../plugins/trmnl/README.md).

## Setup
1. On TRMNL: create a private plugin with the strategy "Webhook" and copy its Webhook URL.
2. In the app: Settings, Plugins, TRMNL: paste the URL (one for all watches, or one per watch), turn "Send to TRMNL" on.
3. Optional: the minimum minutes between sends (6 to 240, default 10).

## FAQ
| Question | Answer |
|---|---|
| How often does it send? | Only when a value changed, at most once per interval, and under TRMNL's 12 requests an hour. |
| It stopped sending | TRMNL refused or was unreachable: the plugin waits and tries again. The App log says why, never the URL. |
| Does it send when the app is closed? | The app wakes it about every 15 minutes to send what changed. |
| Is the webhook URL safe? | It stays on the phone; logs and storage keys use a short hash of it. |
