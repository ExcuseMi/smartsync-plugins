# TRMNL plugin changelog

Tags `trmnl-v<version>`.

## 0.1.0
- Pushes each watch's steps, heart rate, battery and last sync to a TRMNL private plugin (webhook), only when changed,
  at most once per minimum interval, with back-off on failures.
- Builds against the plugin-api module of this repository.
- Sends on the host's periodic wake-up (`WatchEvent.Wake`, every 15 minutes), also while the app is stopped; details
  page metadata and a guide (`docs/trmnl.md`).
