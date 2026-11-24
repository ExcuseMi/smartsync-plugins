package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.avmedia.smartsync.plugin.api.WatchId

/**
 * The Home Assistant dashboard of one watch, built only from the entities that watch has (object id to entity id).
 * Pure: no network. The config carries a marker so the plugin only ever changes or removes a dashboard it made.
 */
object Dashboard {
    const val MARKER_KEY = "smartsync_plugin"
    private const val MARKER_VERSION = 1L
    const val DEFAULT_STEP_GOAL = 8000

    /** The dashboard's address: "smartsync-" and the watch id without its "gshock-" ("smartsync-aabbccddeeff"). */
    fun urlPath(watch: WatchId): String =
        "smartsync-" + watch.key.lowercase().removePrefix("gshock-").map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("").trim('-').ifEmpty { "watch" }

    /** True only for a config this plugin wrote for [watch]. */
    fun isOurs(config: Any?, watch: WatchId): Boolean {
        val marker = (config as? Map<*, *>)?.get(MARKER_KEY) as? Map<*, *> ?: return false
        return marker["device"] == watch.key
    }

    /** The control rows of the dashboard, in order: setting keys of the host's catalogue. */
    val CONTROLS = listOf("watch_face", "custom_slot", "auto_light", "light_duration", "hr_measurement", "smart_alarm", "smart_alarm_time", "bedtime")

    fun build(watch: WatchId, title: String, found: Map<String, String>, stepGoal: Int?): Map<String, Any?> {
        fun ids(vararg objs: String) = objs.mapNotNull { found[it] }
        fun named(vararg pairs: Pair<String, String>) = pairs.mapNotNull { (o, n) -> found[o]?.let { mapOf("entity" to it, "name" to n) } }

        val today = listOfNotNull(
            found["steps_today"]?.let { gauge("Steps today", it, stepGoal?.takeIf { g -> g > 0 } ?: DEFAULT_STEP_GOAL) },
            glance("Today", named("energy_today" to "kcal", "active_minutes" to "Active", "walk_minutes" to "Walk", "distance_today" to "Distance")),
            glance("Heart rate", named("heart_rate" to "Now", "hr_rest" to "Resting", "hr_min" to "Min", "hr_max" to "Max")),
            history("Heart rate today", ids("heart_rate"), 24),
            glance(
                "Last night",
                named("sleep_score" to "Score", "sleep_total" to "Total", "sleep_deep" to "Deep", "sleep_light" to "Light", "sleep_rem" to "REM", "sleep_awake" to "Awake"),
            ),
        )
        val watchCards = listOfNotNull(
            entities("Controls", CONTROLS.mapNotNull { found["setting_$it"] }),
            entities("Battery and connection", ids("battery", "connected", "last_sync", "phone_battery", "temperature")),
            history("Battery, 7 days", ids("battery"), 168),
            entities("Last workout", ids("workout_sport", "workout_start", "workout_duration", "workout_hr_avg", "workout_hr_max")),
        )
        val views = listOf(Triple("Today", "today", "mdi:walk") to today, Triple("Watch", "watch", "mdi:watch") to watchCards)
            .filter { it.second.isNotEmpty() }
            .map { (v, cards) -> linkedMapOf("title" to v.first, "path" to v.second, "icon" to v.third, "cards" to cards) }
        return linkedMapOf(
            MARKER_KEY to linkedMapOf("device" to watch.key, "version" to MARKER_VERSION),
            "title" to title,
            "views" to views,
        )
    }

    /** A config with at least one card. */
    fun hasCards(config: Map<String, Any?>): Boolean = (config["views"] as? List<*>).orEmpty().any { ((it as? Map<*, *>)?.get("cards") as? List<*>)?.isNotEmpty() == true }

    /**
     * Object id to the entity id Home Assistant gives each entity of [watch] on first discovery, for the YAML (which
     * cannot read the entity registry without an administrator's token).
     */
    fun predictedEntities(watch: PairedWatch, catalog: List<SettingInfo>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in Entities.valuesFor(watch.capabilities)) {
            out[e.obj] = Entities.defaultEntityId(e, watch.name) ?: "${e.component}.${Entities.slug(watch.name + " " + e.name)}"
        }
        for (s in catalog) {
            val component = when (s.type) {
                SettingType.CHOICE -> "select"
                SettingType.BOOL -> "switch"
                SettingType.WHOLE -> if (s.only != null) "select" else "number"
                SettingType.TIME -> "text"
            }
            out["setting_" + s.key] = "$component.${Entities.slug(watch.name + " " + Discovery.label(s.key))}"
        }
        return out
    }

    private fun gauge(name: String, entity: String, max: Int) = linkedMapOf(
        "type" to "gauge", "name" to name, "entity" to entity, "min" to 0, "max" to max, "needle" to false,
        "severity" to linkedMapOf("green" to max, "yellow" to max / 2, "red" to 0),
    )

    private fun glance(title: String, items: List<Map<String, String>>) =
        if (items.isEmpty()) null else linkedMapOf("type" to "glance", "title" to title, "columns" to 4, "entities" to items)

    private fun entities(title: String, ids: List<String>) =
        if (ids.isEmpty()) null else linkedMapOf("type" to "entities", "title" to title, "entities" to ids)

    private fun history(title: String, ids: List<String>, hours: Int) =
        if (ids.isEmpty()) null else linkedMapOf("type" to "history-graph", "title" to title, "hours_to_show" to hours, "entities" to ids)
}
