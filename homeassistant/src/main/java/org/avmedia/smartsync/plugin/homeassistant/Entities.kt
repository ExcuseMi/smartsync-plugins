package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability

/**
 * One value the plugin publishes: a retained state topic and, with discovery, a Home Assistant sensor or binary
 * sensor. [needs] is the capability a watch must have for it (null: every watch).
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
)

object Entities {
    private const val DIAG = "diagnostic"
    private const val MEAS = "measurement"
    private const val TOTAL = "total_increasing"

    /** Every value, in publishing order. Adding a value is adding a row. */
    val VALUES: List<ValueEntity> = listOf(
        ValueEntity("connected", "Connected", "watch.connected", null, "binary_sensor", "connectivity", icon = "mdi:watch-vibrate", category = DIAG),
        ValueEntity("last_sync", "Last sync", "app.last_sync", null, deviceClass = "timestamp", icon = "mdi:sync", category = DIAG),
        ValueEntity("phone_battery", "Phone battery", "phone.battery", null, deviceClass = "battery", unit = "%", stateClass = MEAS, category = DIAG),
        ValueEntity("battery", "Battery", "watch.battery", Capability.BATTERY, deviceClass = "battery", unit = "%", stateClass = MEAS),
        ValueEntity("temperature", "Temperature", "watch.temperature", Capability.TEMPERATURE, deviceClass = "temperature", unit = "°C", stateClass = MEAS),
        ValueEntity("firmware", "Firmware", "watch.firmware", Capability.FIRMWARE_INFO, icon = "mdi:chip", category = DIAG),
        ValueEntity("home_city", "Home city", "watch.home_city", Capability.HOME_TIME, icon = "mdi:city"),
        ValueEntity("timer", "Timer", "watch.timer_seconds", Capability.TIMER, deviceClass = "duration", unit = "s", icon = "mdi:timer-outline"),
        ValueEntity("steps_today", "Steps today", "health.steps_today", Capability.STEPS, unit = "steps", stateClass = TOTAL, icon = "mdi:shoe-print"),
        ValueEntity("step_goal", "Step goal", "health.step_goal", Capability.HEALTH_LOG, unit = "steps", icon = "mdi:flag-checkered"),
        ValueEntity("energy_today", "Energy today", "health.kcal_today", Capability.HEALTH_LOG, unit = "kcal", stateClass = TOTAL, icon = "mdi:fire"),
        ValueEntity("distance_today", "Distance today", "health.distance_m", Capability.HEALTH_LOG, deviceClass = "distance", unit = "m", stateClass = TOTAL),
        ValueEntity("active_minutes", "Active minutes", "health.active_minutes", Capability.HEALTH_LOG, deviceClass = "duration", unit = "min", stateClass = TOTAL),
        ValueEntity("heart_rate", "Heart rate", "health.hr_recent_avg", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse"),
        ValueEntity("hr_rest", "Resting heart rate", "health.hr_rest", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse"),
        ValueEntity("hr_min", "Min heart rate", "health.hr_min", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse"),
        ValueEntity("hr_max", "Max heart rate", "health.hr_max", Capability.HEART_RATE, unit = "bpm", stateClass = MEAS, icon = "mdi:heart-pulse"),
        ValueEntity("sleep_score", "Sleep score", "health.sleep_score", Capability.SLEEP, stateClass = MEAS, icon = "mdi:sleep"),
        ValueEntity("sleep_total", "Sleep total", "health.sleep_total_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS),
        ValueEntity("sleep_deep", "Deep sleep", "health.sleep_deep_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS),
        ValueEntity("sleep_light", "Light sleep", "health.sleep_light_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS),
        ValueEntity("sleep_rem", "REM sleep", "health.sleep_rem_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS),
        ValueEntity("sleep_awake", "Awake in the night", "health.sleep_awake_min", Capability.SLEEP, deviceClass = "duration", unit = "min", stateClass = MEAS),
        ValueEntity("spo2", "Blood oxygen", "health.spo2", Capability.BLOOD_OXYGEN, unit = "%", stateClass = MEAS, icon = "mdi:water-percent"),
        ValueEntity("workout_sport", "Last workout", "workout.sport", Capability.WORKOUTS, icon = "mdi:run"),
        ValueEntity("workout_start", "Last workout start", "workout.start", Capability.WORKOUTS, icon = "mdi:clock-start"),
        ValueEntity("workout_duration", "Last workout duration", "workout.duration_seconds", Capability.WORKOUTS, deviceClass = "duration", unit = "s"),
        ValueEntity("workout_hr_avg", "Last workout average heart rate", "workout.hr_avg", Capability.WORKOUTS, unit = "bpm", icon = "mdi:heart-pulse"),
        ValueEntity("workout_hr_max", "Last workout max heart rate", "workout.hr_max", Capability.WORKOUTS, unit = "bpm", icon = "mdi:heart-pulse"),
    )

    /** The value keys the plugin asks the host to read. */
    val VALUE_KEYS: Set<String> = VALUES.map { it.key }.toSet()

    /** The values a watch with [caps] has. A watch never connected (no caps yet) gets only those every watch has. */
    fun valuesFor(caps: Set<Capability>): List<ValueEntity> = VALUES.filter { it.needs == null || it.needs in caps }
}
