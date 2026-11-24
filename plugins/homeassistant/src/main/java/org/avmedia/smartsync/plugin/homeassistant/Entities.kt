package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.ValueType

/**
 * One value the plugin publishes: a retained state topic and, with discovery, a Home Assistant sensor or binary
 * sensor. [needs] is the capability a watch must have for it (null: every watch).
 *
 * [entityId] is the end of the entity id Home Assistant gives it on first discovery, after the watch's name
 * ("steps_today" gives sensor.casio_gbx_h5600_steps_today): the id the app's mobile app integration used for the same
 * value, so dashboards, automations and long-term statistics (same id, unit and state class) carry over once the old
 * mobile app device is removed. Null: Home Assistant picks one from the name.
 */
data class ValueEntity(
    val obj: String,
    val name: String,
    /** The host's value key ([org.avmedia.smartsync.plugin.api.WatchValues]). */
    val key: String,
    val needs: Capability?,
    val component: String = "sensor",
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val icon: String? = null,
    val category: String? = null,
    val entityId: String? = null,
    val convert: Convert = Convert.NONE,
)

/** What happens to a host value before it is published as the state. */
enum class Convert {
    NONE,

    /** The host counts seconds, the entity is in whole minutes. */
    SECONDS_TO_MINUTES,

    /**
     * A time stamp, read by the host's type of the value ([org.avmedia.smartsync.plugin.api.ValueType]): a
     * `LOCAL_DATE_TIME` (the watch's local time) gets the phone's offset, a `DATE_TIME` keeps its own. Home Assistant
     * refuses a timestamp without an offset.
     */
    TIMESTAMP,
}

object Entities {
    private const val DIAG = "diagnostic"
    private const val MEAS = "measurement"
    private const val TOTAL = "total_increasing"
    private val HL = Capability.HEALTH_LOG

    /** Every value, in publishing order. Adding a value is adding a row. */
    val VALUES: List<ValueEntity> = listOf(
        ValueEntity("connected", "Connected", "watch.connected", null, "binary_sensor", "connectivity", icon = "mdi:watch-vibrate", category = DIAG, entityId = "watch_connected"),
        ValueEntity("last_sync", "Last sync", "app.last_sync", null, deviceClass = "timestamp", icon = "mdi:sync", category = DIAG, entityId = "last_sync", convert = Convert.TIMESTAMP),
        ValueEntity("phone_battery", "Phone battery", "phone.battery", null, deviceClass = "battery", unit = "%", stateClass = MEAS, category = DIAG, entityId = "phone_battery"),
        ValueEntity("notifications_supported", "Notifications supported", "watch.notifications_supported", null, "binary_sensor", icon = "mdi:bell-ring", category = DIAG, entityId = "notifications_supported"),
        ValueEntity("battery", "Battery", "watch.battery", Capability.BATTERY, deviceClass = "battery", unit = "%", stateClass = MEAS, entityId = "battery"),
        ValueEntity("temperature", "Temperature", "watch.temperature", Capability.TEMPERATURE, deviceClass = "temperature", unit = "°C", stateClass = MEAS, entityId = "temperature"),
        ValueEntity("firmware", "Firmware", "watch.firmware", Capability.FIRMWARE_INFO, icon = "mdi:chip", category = DIAG, entityId = "watch_firmware"),
        ValueEntity("home_city", "Home city", "watch.home_city", Capability.HOME_TIME, icon = "mdi:city", entityId = "home_city"),
        ValueEntity("home_dst", "Home daylight saving", "watch.dst.home", Capability.HOME_TIME, "binary_sensor", icon = "mdi:weather-sunny", entityId = "home_daylight_saving"),
        ValueEntity("timer", "Timer", "watch.timer_seconds", Capability.TIMER, deviceClass = "duration", unit = "s", icon = "mdi:timer-outline", entityId = "watch_timer"),
        ValueEntity("steps_today", "Steps today", "health.steps_today", Capability.STEPS, unit = "steps", stateClass = TOTAL, icon = "mdi:shoe-print", entityId = "steps_today"),
        ValueEntity("step_goal", "Step goal", "health.step_goal", HL, unit = "steps", icon = "mdi:flag-checkered", entityId = "step_goal"),
        ValueEntity("energy_today", "Energy today", "health.kcal_today", HL, unit = "kcal", stateClass = TOTAL, icon = "mdi:fire", entityId = "energy_today"),
        ValueEntity("energy_total", "Total energy today (incl. resting)", "health.energy_total_kcal", HL, unit = "kcal", stateClass = TOTAL, icon = "mdi:fire", entityId = "total_energy_today_incl_resting"),
        ValueEntity("energy_active", "Active energy today", "health.energy_active_kcal", HL, unit = "kcal", stateClass = TOTAL, icon = "mdi:fire-circle", entityId = "active_energy_today"),
        ValueEntity("distance_today", "Distance today", "health.distance_m", HL, deviceClass = "distance", unit = "m", stateClass = TOTAL, entityId = "distance_today"),
        ValueEntity("active_minutes", "Active minutes", "health.active_minutes", HL, deviceClass = "duration", unit = "min", stateClass = TOTAL, entityId = "active_minutes"),
        ValueEntity("walk_minutes", "Walking today", "health.walk_seconds", HL, deviceClass = "duration", unit = "min", stateClass = TOTAL, icon = "mdi:walk", entityId = "walking_today", convert = Convert.SECONDS_TO_MINUTES),
        ValueEntity("run_minutes", "Running today", "health.run_seconds", HL, deviceClass = "duration", unit = "min", stateClass = TOTAL, icon = "mdi:run-fast", entityId = "running_today", convert = Convert.SECONDS_TO_MINUTES),
        ValueEntity("still_minutes", "Still today", "health.still_seconds", HL, deviceClass = "duration", unit = "min", stateClass = TOTAL, icon = "mdi:seat", entityId = "still_today", convert = Convert.SECONDS_TO_MINUTES),
        ValueEntity("health_recorded_at", "Health data recorded", "health.recorded_at", HL, deviceClass = "timestamp", icon = "mdi:clock-check", category = DIAG, entityId = "health_data_recorded", convert = Convert.TIMESTAMP),
        ValueEntity("stairs_up", "Floors up (unconfirmed)", "health.stairs_up", HL, stateClass = MEAS, icon = "mdi:stairs-up", entityId = "floors_up_unconfirmed"),
        ValueEntity("stairs_down", "Floors down (unconfirmed)", "health.stairs_down", HL, stateClass = MEAS, icon = "mdi:stairs-down", entityId = "floors_down_unconfirmed"),
        ValueEntity("heart_rate", "Heart rate", "health.hr_recent_avg", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse", entityId = "recent_heart_rate"),
        ValueEntity("hr_rest", "Resting heart rate", "health.hr_rest", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse", entityId = "resting_heart_rate"),
        ValueEntity("hr_min", "Min heart rate", "health.hr_min", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse", entityId = "min_heart_rate"),
        ValueEntity("hr_max", "Max heart rate", "health.hr_max", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse", entityId = "max_heart_rate"),
        ValueEntity("cardio_load", "Cardio load ratio", "health.cardio_load_ratio", Capability.HEART_RATE, stateClass = MEAS, icon = "mdi:heart-flash", entityId = "cardio_load_ratio"),
        ValueEntity("sleep_score", "Sleep score", "health.sleep_score", Capability.SLEEP, stateClass = MEAS, icon = "mdi:sleep", entityId = "sleep_score"),
        ValueEntity("recharge_status", "Nightly Recharge status", "health.recharge_status", Capability.SLEEP, stateClass = MEAS, icon = "mdi:battery-heart-variant", entityId = "nightly_recharge_status"),
        ValueEntity("sleep_fell_asleep", "Fell asleep (unconfirmed)", "health.sleep_fell_asleep", Capability.SLEEP, deviceClass = "timestamp", icon = "mdi:bed-clock", entityId = "fell_asleep_unconfirmed", convert = Convert.TIMESTAMP),
        ValueEntity("sleep_woke_up", "Woke up (unconfirmed)", "health.sleep_woke_up", Capability.SLEEP, deviceClass = "timestamp", icon = "mdi:alarm", entityId = "woke_up_unconfirmed", convert = Convert.TIMESTAMP),
        ValueEntity("sleep_total", "Sleep total", "health.sleep_total_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS, entityId = "sleep_total_stages_unconfirmed"),
        ValueEntity("sleep_deep", "Deep sleep", "health.sleep_deep_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS, entityId = "deep_sleep"),
        ValueEntity("sleep_light", "Light sleep", "health.sleep_light_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS, entityId = "light_sleep"),
        ValueEntity("sleep_rem", "REM sleep", "health.sleep_rem_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS, entityId = "rem_sleep"),
        ValueEntity("sleep_awake", "Awake in the night", "health.sleep_awake_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS, entityId = "sleep_interruptions"),
        ValueEntity("sleep_hr", "Heart rate in the night", "health.sleep_hr", Capability.SLEEP, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse", entityId = "heart_rate_in_the_night"),
        ValueEntity("sleep_breathing", "Breathing rate in the night", "health.sleep_breathing", Capability.SLEEP, unit = "br/min", stateClass = MEAS, icon = "mdi:lungs", entityId = "breathing_rate_in_the_night"),
        ValueEntity("breathing_exercise", "Last breathing exercise", "health.breathing_exercise_at", Capability.SLEEP, deviceClass = "timestamp", icon = "mdi:meditation", entityId = "last_breathing_exercise", convert = Convert.TIMESTAMP),
        ValueEntity("spo2", "Blood oxygen", "health.spo2", Capability.BLOOD_OXYGEN, unit = "%", stateClass = MEAS, icon = "mdi:water-percent", entityId = "blood_oxygen"),
        ValueEntity("workout_sport", "Last workout", "workout.sport", Capability.WORKOUTS, icon = "mdi:run", entityId = "last_workout"),
        ValueEntity("workout_start", "Last workout start", "workout.start", Capability.WORKOUTS, deviceClass = "timestamp", icon = "mdi:clock-start", entityId = "last_workout_start", convert = Convert.TIMESTAMP),
        ValueEntity("workout_duration", "Last workout duration", "workout.duration_seconds", Capability.WORKOUTS, deviceClass = "duration", unit = "min", icon = "mdi:timer", entityId = "last_workout_duration", convert = Convert.SECONDS_TO_MINUTES),
        ValueEntity("workout_distance", "Last workout distance", "workout.distance", Capability.WORKOUTS, icon = "mdi:map-marker-distance", entityId = "last_workout_distance_unconfirmed_unit_likely_km"),
        ValueEntity("workout_kcal", "Last workout energy", "workout.kcal", Capability.WORKOUTS, unit = "kcal", icon = "mdi:fire", entityId = "last_workout_energy_unconfirmed"),
        ValueEntity("workout_hr_avg", "Last workout average heart rate", "workout.hr_avg", Capability.WORKOUTS, unit = "bpm", icon = "mdi:heart-pulse", entityId = "last_workout_average_heart_rate"),
        ValueEntity("workout_hr_max", "Last workout max heart rate", "workout.hr_max", Capability.WORKOUTS, unit = "bpm", icon = "mdi:heart-pulse", entityId = "last_workout_max_heart_rate"),
    )

    /** The value keys the plugin asks the host to read. */
    val VALUE_KEYS: Set<String> = VALUES.map { it.key }.toSet()

    /** Health data ("Share health data with Home Assistant"): the health values and the last workout. */
    fun isHealth(e: ValueEntity): Boolean = e.key.startsWith("health.") || e.key.startsWith("workout.")

    /** The value keys without the health ones, for a person who keeps health data out of Home Assistant. */
    val VALUE_KEYS_WITHOUT_HEALTH: Set<String> = VALUES.filterNot { isHealth(it) }.map { it.key }.toSet()

    /**
     * The values a watch with [caps] has; without [health] the health values are left out. A watch never connected
     * (no caps yet) gets only those every watch has.
     */
    fun valuesFor(caps: Set<Capability>, health: Boolean = true): List<ValueEntity> =
        VALUES.filter { (it.needs == null || it.needs in caps) && (health || !isHealth(it)) }

    /** "CASIO GBX-H5600" to "casio_gbx_h5600", as Home Assistant makes an entity id from a name. */
    fun slug(text: String): String =
        text.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").replace(Regex("_+"), "_").trim('_')

    /** The entity id [e] asks for on [watchName]'s device; null when Home Assistant picks it. */
    fun defaultEntityId(e: ValueEntity, watchName: String): String? {
        val suffix = e.entityId ?: return null
        val device = slug(watchName).ifEmpty { return null }
        return "${e.component}.${device}_$suffix"
    }

    /**
     * [v] as the entity's state wants it ([ValueEntity.convert]); null when it cannot be converted. [type] is the host's
     * type of the value ([org.avmedia.smartsync.plugin.api.ValueInfo.type]); null when the host did not say.
     */
    fun convert(e: ValueEntity, v: Any?, zone: java.time.ZoneId, type: ValueType? = null): Any? = when (e.convert) {
        Convert.NONE -> v
        Convert.SECONDS_TO_MINUTES -> (v as? Number)?.toLong()?.div(60)
        Convert.TIMESTAMP -> (v as? String)?.trim()?.let { s ->
            fun offset() = runCatching { java.time.OffsetDateTime.parse(s).toString() }.getOrNull()
            fun local() = runCatching { java.time.LocalDateTime.parse(s).atZone(zone).toOffsetDateTime().toString() }.getOrNull()
            when (type) {
                ValueType.LOCAL_DATE_TIME -> local()
                ValueType.DATE_TIME -> offset()
                else -> offset() ?: local()
            }
        }
    }
}
