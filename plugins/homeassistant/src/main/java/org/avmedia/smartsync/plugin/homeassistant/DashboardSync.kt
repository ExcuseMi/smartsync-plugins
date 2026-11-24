package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.WatchId

/** What a dashboard action did, in plain words for the settings screen. */
data class DashboardResult(val ok: Boolean, val message: String, val yaml: String = "")

/**
 * Creates, updates and removes the dashboard of one watch over Home Assistant's websocket API (an administrator's
 * token), only ever a dashboard this plugin made ([Dashboard.isOurs]); and writes the same dashboard as YAML, which
 * needs no administrator.
 */
class DashboardSync(private val channel: () -> HaChannel?) {
    /** Creates the dashboard of [watch] or brings its own one up to date with the entities the watch has now. */
    suspend fun sync(watch: PairedWatch, stepGoal: Int?): DashboardResult {
        val ch = channel()?.takeIf { it.ready } ?: return DashboardResult(false, NOT_CONNECTED)
        val reg = ch.call(mapOf("type" to "config/entity_registry/list"))
        if (!reg.success) return fail(reg)
        val found = registered(reg.result, watch.id)
        if (found.isEmpty()) return DashboardResult(false, "Home Assistant has no entities of this watch yet")
        val config = Dashboard.build(watch.id, watch.name, found, stepGoal)
        val path = Dashboard.urlPath(watch.id)
        val list = ch.call(mapOf("type" to "lovelace/dashboards/list"))
        if (!list.success) return fail(list)
        val present = objects(list.result).firstOrNull { it["url_path"] == path }
        val verb = if (present != null) {
            val old = ch.call(mapOf("type" to "lovelace/config", "url_path" to path)).takeIf { it.success }?.result
            if (!Dashboard.isOurs(old, watch.id)) return DashboardResult(false, "A dashboard at /$path that this plugin did not make: left as it is")
            if (old == JsonReader.read(Json.write(config))) return DashboardResult(true, "Dashboard up to date")
            "updated"
        } else {
            val made = ch.call(
                mapOf(
                    "type" to "lovelace/dashboards/create", "url_path" to path, "title" to watch.name, "icon" to "mdi:watch",
                    "show_in_sidebar" to true, "require_admin" to false,
                ),
            )
            if (!made.success) return fail(made)
            "created"
        }
        val saved = ch.call(mapOf("type" to "lovelace/config/save", "url_path" to path, "config" to config))
        if (!saved.success) return fail(saved)
        return DashboardResult(true, "Dashboard $verb")
    }

    /** Removes the dashboard of [watch] when this plugin made it; any other dashboard stays. */
    suspend fun remove(watch: WatchId): DashboardResult {
        val ch = channel()?.takeIf { it.ready } ?: return DashboardResult(false, NOT_CONNECTED)
        val path = Dashboard.urlPath(watch)
        val list = ch.call(mapOf("type" to "lovelace/dashboards/list"))
        if (!list.success) return fail(list)
        val present = objects(list.result).firstOrNull { it["url_path"] == path } ?: return DashboardResult(true, "No dashboard")
        val old = ch.call(mapOf("type" to "lovelace/config", "url_path" to path)).takeIf { it.success }?.result
        if (!Dashboard.isOurs(old, watch)) return DashboardResult(true, "A dashboard at /$path that this plugin did not make: left as it is")
        val gone = ch.call(mapOf("type" to "lovelace/dashboards/delete", "dashboard_id" to present["id"]))
        return if (gone.success) DashboardResult(true, "Dashboard removed") else fail(gone)
    }

    /** The dashboard as YAML, from the entities Home Assistant has now (any token can read the states). */
    suspend fun yaml(watch: PairedWatch, catalog: List<SettingInfo>, stepGoal: Int?): DashboardResult {
        val ch = channel()?.takeIf { it.ready } ?: return DashboardResult(false, NOT_CONNECTED)
        val states = ch.call(mapOf("type" to "get_states"))
        if (!states.success) return fail(states)
        val have = objects(states.result).mapNotNull { it["entity_id"] as? String }.toSet()
        val found = Dashboard.predictedEntities(watch, catalog).filterValues { it in have }
        if (found.isEmpty()) return DashboardResult(false, "Home Assistant has no entities of this watch yet")
        return DashboardResult(true, "Copied", Yaml.dump(Dashboard.build(watch.id, watch.name, found, stepGoal)))
    }

    /** Object id to entity id of the plugin's enabled entities of [watch] in the entity registry. */
    private fun registered(result: Any?, watch: WatchId): Map<String, String> {
        val prefix = "smartsync_${Topics.node(watch)}_"
        val out = LinkedHashMap<String, String>()
        for (e in objects(result)) {
            if (e["platform"] != "mqtt" || e["disabled_by"] != null) continue
            val unique = e["unique_id"] as? String ?: continue
            val entity = e["entity_id"] as? String ?: continue
            if (unique.startsWith(prefix)) out[unique.removePrefix(prefix)] = entity
        }
        return out
    }

    private fun objects(result: Any?): List<Map<*, *>> = (result as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun fail(r: HaReply) = DashboardResult(
        false,
        if (r.code == "unauthorized") NO_ADMIN else "Home Assistant said: ${r.error ?: r.code ?: "error"}",
    )

    companion object {
        const val NOT_CONNECTED = "Home Assistant is not connected (address and token in the settings above)"
        const val NO_ADMIN = "Making dashboards needs an administrator's token; copy the YAML instead"
    }
}
