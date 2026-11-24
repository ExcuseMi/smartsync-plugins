package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.avmedia.smartsync.plugin.api.WatchValues

/**
 * Home Assistant MQTT discovery for one watch, built only from what the host says: the watch's capabilities, its
 * setting catalogue and its last values. One device per watch; entities only for what that watch has.
 */
class Discovery(
    private val topics: Topics,
    private val pluginVersion: String,
    private val expireAfterSec: Int? = null,
    /** Health entities too ("Share health data with Home Assistant"). */
    private val health: Boolean = true,
) {
    /** Config topic to retained JSON payload, in a stable order. */
    fun build(watch: PairedWatch, values: WatchValues, catalog: List<SettingInfo>): Map<String, String> {
        val w = watch.id
        val node = Topics.node(w)
        val out = LinkedHashMap<String, String>()
        val device = device(watch, values)
        fun put(component: String, obj: String, name: String, extra: Map<String, Any?>) {
            val m = LinkedHashMap<String, Any?>()
            m["name"] = name
            m["unique_id"] = "smartsync_${node}_${Topics.safe(obj)}"
            m["availability"] = listOf(mapOf("topic" to topics.bridgeAvailability), mapOf("topic" to topics.availability(w)))
            m["availability_mode"] = "all"
            m["device"] = device
            m["origin"] = ORIGIN + ("sw_version" to pluginVersion)
            extra.forEach { (k, v) -> if (v != null) m[k] = v }
            out[topics.config(component, w, obj)] = Json.write(m)
        }

        for (e in Entities.valuesFor(watch.capabilities, health)) {
            val extra = linkedMapOf<String, Any?>(
                "state_topic" to topics.state(w, e.obj),
                "device_class" to e.deviceClass,
                "unit_of_measurement" to e.unit,
                "state_class" to e.stateClass,
                "icon" to e.icon,
                "entity_category" to e.category,
            )
            if (e.component == "binary_sensor") { extra["payload_on"] = SettingValue.ON; extra["payload_off"] = SettingValue.OFF }
            if (expireAfterSec != null) extra["expire_after"] = expireAfterSec
            // Only read on first discovery: the id the mobile app integration used ([ValueEntity.entityId]).
            extra["default_entity_id"] = Entities.defaultEntityId(e, watch.name)
            put(e.component, e.obj, e.name, extra)
        }

        for (s in catalog) {
            val obj = "setting_" + s.key
            val base = linkedMapOf<String, Any?>(
                "state_topic" to topics.settingState(w, s.key),
                "command_topic" to topics.settingSet(w, s.key),
                "entity_category" to "config",
            )
            val name = label(s.label)
            when (s.type) {
                SettingType.CHOICE -> if (s.choices.isNotEmpty()) put("select", obj, name, base + ("options" to s.choices))
                SettingType.BOOL -> put(
                    "switch", obj, name,
                    base + mapOf("payload_on" to SettingValue.ON, "payload_off" to SettingValue.OFF, "state_on" to SettingValue.ON, "state_off" to SettingValue.OFF),
                )
                SettingType.WHOLE -> {
                    val only = s.only
                    val range = s.range
                    when {
                        only != null -> if (only.isNotEmpty()) put("select", obj, name, base + ("options" to only.sorted().map { it.toString() }))
                        range != null -> put("number", obj, name, base + mapOf("min" to range.first, "max" to range.last, "step" to 1, "mode" to "box"))
                        else -> put("number", obj, name, base + mapOf("min" to Int.MIN_VALUE, "max" to Int.MAX_VALUE, "step" to 1, "mode" to "box"))
                    }
                }
                // Home Assistant has no MQTT time entity: a text with the HH:MM pattern.
                SettingType.TIME -> put("text", obj, name, base + mapOf("min" to 5, "max" to 5, "pattern" to SettingValue.TIME_PATTERN))
            }
        }

        if (Capability.NOTIFICATIONS in watch.capabilities) {
            put(
                "notify", "notify", "Notification",
                mapOf("command_topic" to topics.notify(w), "command_template" to NOTIFY_TEMPLATE, "icon" to "mdi:message-text"),
            )
        }
        put(
            "event", "button", "Button",
            mapOf("state_topic" to topics.button(w), "event_types" to listOf(EVENT_PRESS), "icon" to "mdi:gesture-tap-button"),
        )
        // A device trigger as well, so "the watch button was pressed" is offered in the automation editor.
        out[topics.config("device_automation", w, "button_press")] = Json.write(
            linkedMapOf(
                "automation_type" to "trigger", "type" to "button_short_press", "subtype" to "button_1",
                "topic" to topics.button(w), "payload" to Bridge.BUTTON_PAYLOAD, "device" to device,
                "origin" to ORIGIN + ("sw_version" to pluginVersion),
            ),
        )
        put(
            "button", "refresh", "Read values",
            mapOf("command_topic" to topics.refresh(w), "payload_press" to "PRESS", "icon" to "mdi:refresh", "entity_category" to "diagnostic"),
        )
        put(
            "sensor", "last_command", "Last command",
            mapOf(
                "state_topic" to topics.result(w), "value_template" to "{{ value_json.status }}",
                "json_attributes_topic" to topics.result(w), "icon" to "mdi:console", "entity_category" to "diagnostic",
            ),
        )
        return out
    }

    private fun device(watch: PairedWatch, values: WatchValues): Map<String, Any?> {
        val model = (values["watch.model"] as? String)?.takeIf { it.isNotBlank() } ?: watch.name.removePrefix("CASIO").trim().ifEmpty { watch.name }
        val m = linkedMapOf<String, Any?>(
            "identifiers" to listOf("smartsync_" + Topics.node(watch.id)),
            "name" to watch.name.ifBlank { model },
            "manufacturer" to "CASIO",
            "model" to model,
        )
        (values["watch.firmware"] as? String)?.takeIf { it.isNotBlank() }?.let { m["sw_version"] = it }
        return m
    }

    companion object {
        const val EVENT_PRESS = "press"

        /** What Home Assistant's notify action sends: its title and message as JSON ([NotifyPayload]). */
        const val NOTIFY_TEMPLATE = "{\"title\": {{ title | tojson }}, \"message\": {{ message | tojson }}}"
        private val ORIGIN = mapOf("name" to "Smart Sync Home Assistant plugin", "support_url" to "https://github.com/ExcuseMi/smartsync-plugins")

        /** "watch_face" to "Watch face"; the host's label ("Watch face") stays as it is. */
        fun label(key: String): String = key.replace('_', ' ').replace('.', ' ').trim().replaceFirstChar { it.uppercase() }
    }
}
