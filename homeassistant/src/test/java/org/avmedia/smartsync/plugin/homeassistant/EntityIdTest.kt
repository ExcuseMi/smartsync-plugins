package org.avmedia.smartsync.plugin.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The entities keep the ids, units and state classes of the app's mobile app integration, so dashboards, automations
 * and long-term statistics carry over once the old device is removed. [OLD] is what that integration made for a
 * GBX-H5600 (read from a Home Assistant with it, 2026-11): entity id, unit, state class, device class.
 */
class EntityIdTest {
    data class Old(val id: String, val unit: String?, val stateClass: String?, val deviceClass: String?)

    private val host = FakeHost()
    private val link = FakeLink()
    private val node = "gshock-aabbccddeeff"

    private fun discovered(name: String = "CASIO GBX-H5600"): Map<String, Map<String, Any?>> {
        host.add(node, name, Watches.GBX_CAPS, Watches.GBX_CATALOG)
        Bridge(host, link, Watches.config(), "0.1.0") {}.onConnected()
        return link.configs().mapValues { JsonReader.obj(it.value)!! }
    }

    @Test fun aGbxAsksForEveryIdTheMobileAppIntegrationHad() {
        val configs = discovered().values
        val byId = configs.mapNotNull { c -> (c["default_entity_id"] as? String)?.let { it to c } }.toMap()
        val missing = OLD.map { it.id } - byId.keys
        assertTrue("ids not asked for: $missing", missing.isEmpty())
        for (o in OLD) {
            val c = byId.getValue(o.id)
            assertEquals("${o.id} unit", o.unit, c["unit_of_measurement"])
            assertEquals("${o.id} state class", o.stateClass, c["state_class"])
            assertEquals("${o.id} device class", o.deviceClass, c["device_class"])
        }
        assertEquals("no id asked for twice", byId.size, configs.count { it["default_entity_id"] != null })
    }

    @Test fun controlsAndTheOtherNewEntitiesGetIdsFromHomeAssistant() {
        val c = discovered()
        val fresh = c.filterKeys { k -> listOf("/select/", "/switch/", "/number/", "/text/", "/notify/", "/event/", "/button/").any { it in k } }
        assertTrue(fresh.size > 5)
        fresh.forEach { (k, v) -> assertNull(k, v["default_entity_id"]) }
    }

    @Test fun anotherWatchNameGivesItsOwnIds() {
        val ids = discovered("CASIO GBD-H2000").values.mapNotNull { it["default_entity_id"] as? String }
        assertTrue(ids.contains("sensor.casio_gbd_h2000_steps_today"))
        assertTrue(ids.none { "gbx" in it })
    }

    @Test fun minutesAndOffsetsAreWhatTheEntitiesSay() {
        val zone = ZoneId.of("Europe/Brussels")
        val walk = Entities.VALUES.first { it.obj == "walk_minutes" }
        assertEquals(30L, Entities.convert(walk, 1830, zone))
        assertNull(Entities.convert(walk, "x", zone))
        val recorded = Entities.VALUES.first { it.obj == "health_recorded_at" }
        assertEquals("2026-11-24T14:24+01:00", Entities.convert(recorded, "2026-11-24T14:24:00", zone))
        assertEquals("2026-07-01T08:00+02:00", Entities.convert(recorded, "2026-07-01T08:00", zone))
        assertEquals("2026-11-24T14:24Z", Entities.convert(recorded, "2026-11-24T14:24:00Z", zone))
        assertNull(Entities.convert(recorded, "yesterday", zone))
    }

    @Test fun theWorkoutDurationGoesOutInMinutes() {
        val w = host.add(node, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG)
        host.values[w]!!["workout.duration_seconds"] = 1500
        Bridge(host, link, Watches.config(), "0.1.0") {}.onConnected()
        assertEquals("25", link.last("smartsync/$node/state/workout_duration")?.payload)
    }

    companion object {
        val OLD = listOf(
        Old("binary_sensor.casio_gbx_h5600_watch_connected", null, null, "connectivity"),
        Old("sensor.casio_gbx_h5600_last_sync", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_phone_battery", "%", "measurement", "battery"),
        Old("binary_sensor.casio_gbx_h5600_notifications_supported", null, null, null),
        Old("sensor.casio_gbx_h5600_battery", "%", "measurement", "battery"),
        Old("sensor.casio_gbx_h5600_temperature", "°C", "measurement", "temperature"),
        Old("sensor.casio_gbx_h5600_watch_firmware", null, null, null),
        Old("sensor.casio_gbx_h5600_home_city", null, null, null),
        Old("binary_sensor.casio_gbx_h5600_home_daylight_saving", null, null, null),
        Old("sensor.casio_gbx_h5600_watch_timer", "s", null, "duration"),
        Old("sensor.casio_gbx_h5600_steps_today", "steps", "total_increasing", null),
        Old("sensor.casio_gbx_h5600_step_goal", "steps", null, null),
        Old("sensor.casio_gbx_h5600_energy_today", "kcal", "total_increasing", null),
        Old("sensor.casio_gbx_h5600_total_energy_today_incl_resting", "kcal", "total_increasing", null),
        Old("sensor.casio_gbx_h5600_active_energy_today", "kcal", "total_increasing", null),
        Old("sensor.casio_gbx_h5600_distance_today", "m", "total_increasing", "distance"),
        Old("sensor.casio_gbx_h5600_active_minutes", "min", "total_increasing", "duration"),
        Old("sensor.casio_gbx_h5600_walking_today", "min", "total_increasing", "duration"),
        Old("sensor.casio_gbx_h5600_running_today", "min", "total_increasing", "duration"),
        Old("sensor.casio_gbx_h5600_still_today", "min", "total_increasing", "duration"),
        Old("sensor.casio_gbx_h5600_health_data_recorded", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_floors_up_unconfirmed", null, "measurement", null),
        Old("sensor.casio_gbx_h5600_floors_down_unconfirmed", null, "measurement", null),
        Old("sensor.casio_gbx_h5600_recent_heart_rate", "bpm", "measurement", null),
        Old("sensor.casio_gbx_h5600_resting_heart_rate", "bpm", "measurement", null),
        Old("sensor.casio_gbx_h5600_min_heart_rate", "bpm", "measurement", null),
        Old("sensor.casio_gbx_h5600_max_heart_rate", "bpm", "measurement", null),
        Old("sensor.casio_gbx_h5600_cardio_load_ratio", null, "measurement", null),
        Old("sensor.casio_gbx_h5600_sleep_score", null, "measurement", null),
        Old("sensor.casio_gbx_h5600_nightly_recharge_status", null, "measurement", null),
        Old("sensor.casio_gbx_h5600_fell_asleep_unconfirmed", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_woke_up_unconfirmed", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_sleep_total_stages_unconfirmed", "min", "measurement", "duration"),
        Old("sensor.casio_gbx_h5600_deep_sleep", "min", "measurement", "duration"),
        Old("sensor.casio_gbx_h5600_light_sleep", "min", "measurement", "duration"),
        Old("sensor.casio_gbx_h5600_rem_sleep", "min", "measurement", "duration"),
        Old("sensor.casio_gbx_h5600_sleep_interruptions", "min", "measurement", "duration"),
        Old("sensor.casio_gbx_h5600_heart_rate_in_the_night", "bpm", "measurement", null),
        Old("sensor.casio_gbx_h5600_breathing_rate_in_the_night", "br/min", "measurement", null),
        Old("sensor.casio_gbx_h5600_last_breathing_exercise", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_blood_oxygen", "%", "measurement", null),
        Old("sensor.casio_gbx_h5600_last_workout", null, null, null),
        Old("sensor.casio_gbx_h5600_last_workout_start", null, null, "timestamp"),
        Old("sensor.casio_gbx_h5600_last_workout_duration", "min", null, "duration"),
        Old("sensor.casio_gbx_h5600_last_workout_distance_unconfirmed_unit_likely_km", null, null, null),
        Old("sensor.casio_gbx_h5600_last_workout_energy_unconfirmed", "kcal", null, null),
        Old("sensor.casio_gbx_h5600_last_workout_average_heart_rate", "bpm", null, null),
        Old("sensor.casio_gbx_h5600_last_workout_max_heart_rate", "bpm", null, null),
        )
    }
}
