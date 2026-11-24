package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.WatchId

/**
 * Every topic the plugin uses. Plain topics under [base] work without Home Assistant:
 * `<base>/<watch>/state/<object>` (retained values), `<base>/<watch>/state/setting/<key>`,
 * `<base>/<watch>/set/setting/<key>`, `<base>/<watch>/set/notify`, `<base>/<watch>/set/refresh`,
 * `<base>/<watch>/event/button`, `<base>/<watch>/result`, `<base>/<watch>/availability` and `<base>/bridge/availability`.
 * Discovery configs go to `<prefix>/<component>/<watch>/<object>/config`.
 */
class Topics(base: String, discoveryPrefix: String) {
    val base = BrokerConfig.trimTopic(base)
    val prefix = BrokerConfig.trimTopic(discoveryPrefix)

    val bridgeAvailability = "${this.base}/bridge/availability"

    /** Every command topic of every watch. */
    val commandFilter = "${this.base}/+/set/#"

    fun availability(w: WatchId) = "${root(w)}/availability"
    fun state(w: WatchId, obj: String) = "${root(w)}/state/${safe(obj)}"
    fun settingState(w: WatchId, key: String) = "${root(w)}/state/setting/${safe(key)}"
    fun settingSet(w: WatchId, key: String) = "${root(w)}/set/setting/${safe(key)}"
    fun notify(w: WatchId) = "${root(w)}/set/notify"
    fun refresh(w: WatchId) = "${root(w)}/set/refresh"
    fun button(w: WatchId) = "${root(w)}/event/button"
    fun result(w: WatchId) = "${root(w)}/result"
    fun history(w: WatchId, kind: String, part: Any) = "${root(w)}/history/$kind/$part"
    val haStatus = "$prefix/status"
    fun config(component: String, w: WatchId, obj: String) = "$prefix/$component/${node(w)}/${safe(obj)}/config"

    /** A command topic split into the watch's node id and what follows `set/`; null for any other topic. */
    fun parseCommand(topic: String): Pair<String, List<String>>? {
        if (!topic.startsWith("$base/")) return null
        val rest = topic.removePrefix("$base/").split('/')
        if (rest.size < 3 || rest[1] != "set") return null
        return rest[0] to rest.drop(2)
    }

    private fun root(w: WatchId) = "$base/${node(w)}"

    companion object {
        /** The watch's id as one topic level and a Home Assistant node id: letters, digits, _ and - only. */
        fun node(w: WatchId): String = safe(w.key)

        fun safe(s: String): String = s.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }.joinToString("")
    }
}
