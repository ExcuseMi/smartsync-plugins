# Changelog

## 0.1.0
- Writes steps per hour, heart rate (one record per day), resting heart rate and blood oxygen of finished days, sleep
  sessions (stages only when proven) and workouts (session and energy when its unit is proven) to Health Connect.
- Idempotent: clientRecordId per watch and hour, day, night or workout; a changed value is written with the next
  clientRecordVersion, an unchanged one not at all.
- Settings screen: Health Connect status, permission request (write only), last write. Rationale screen for Health Connect.
- Built against plugin-api 0.1.0 (the plugin-api module of smartsync-plugins); details page metadata and a guide (`docs/healthconnect.md`).
