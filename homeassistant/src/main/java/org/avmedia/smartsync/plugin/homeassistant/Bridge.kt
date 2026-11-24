package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues

/**
 * Between the host and the broker, for one broker setting. Publishes each watch's retained topics (discovery, states,
 * availability) only when they changed, empties the ones a watch no longer has (also of a forgotten watch), and turns
 * command messages into host calls. Thread safe: publishing runs under one lock, host calls that wait run outside it.
 */
class Bridge(
    private val host: PluginHost,
    private val link: MqttLink,
    val config: BrokerConfig,
    pluginVersion: String,
    private val log: (String) -> Unit,
) {
    val topics = Topics(config.baseTopic, config.discoveryPrefix)
    private val discovery = Discovery(topics, pluginVersion)
    private val lock = Any()

    /** What this connection published per topic: the broker keeps retained messages, so equal ones are not sent again. */
    private val sent = HashMap<String, String>()

    /** Connected state from the latest Connected/Disconnected event, newer than [PluginHost.watches] may be. */
    private val connectedNow = HashMap<WatchId, Boolean>()

    /** The last will, and the goodbye of a planned stop: the bridge goes offline, the entities show unavailable. */
    val will: Will get() = Will(topics.bridgeAvailability, OFFLINE)

    /** After every (re)connection: availability, the command subscription, every watch, and the forgotten ones emptied. */
    fun onConnected() = synchronized(lock) {
        sent.clear()
        publish(topics.bridgeAvailability, ONLINE, retain = true)
        if (!link.subscribe(topics.commandFilter)) log("MQTT: could not subscribe to the command topics")
        val paired = host.watches()
        val nodes = paired.map { Topics.node(it.id) }.toSet()
        host.storage().keys().filter { it.startsWith(TOPICS_KEY) }.map { it.removePrefix(TOPICS_KEY) }
            .filter { it !in nodes }.forEach { clearNode(it) }
        paired.forEach { syncLocked(it) }
        while (queued.isNotEmpty() && link.publish(queued.first().topic, queued.first().payload, retain = false)) queued.removeFirst()
    }

    /** [watch]'s values, settings, capabilities or catalogue may have changed. */
    fun sync(watch: WatchId) = synchronized(lock) {
        host.watches().firstOrNull { it.id == watch }?.let { syncLocked(it) }
        Unit
    }

    fun syncAll() = synchronized(lock) { host.watches().forEach { syncLocked(it) } }

    fun connectionChanged(watch: WatchId, connected: Boolean) {
        synchronized(lock) { connectedNow[watch] = connected }
        sync(watch)
    }

    /**
     * A press of the watch button: one event message, never retained. While the broker is away the press is kept
     * (the newest [MAX_QUEUED]) and sent at the next connection, so an automation still runs.
     */
    fun buttonPressed(watch: WatchId) = synchronized(lock) {
        val m = Pending(topics.button(watch), BUTTON_PAYLOAD)
        if (!link.connected || !link.publish(m.topic, m.payload, retain = false)) {
            queued.addLast(m)
            while (queued.size > MAX_QUEUED) queued.removeFirst()
        }
    }

    private data class Pending(val topic: String, val payload: String)

    /** Event messages kept while the broker was away, oldest first. */
    private val queued = ArrayDeque<Pending>()

    /** [watch] was removed from the app: every retained topic it had is emptied, so Home Assistant drops the device. */
    fun removeWatch(watch: WatchId) = synchronized(lock) {
        connectedNow.remove(watch)
        clearNode(Topics.node(watch))
    }

    // Commands

    suspend fun handle(topic: String, payload: String) {
        val (node, rest) = topics.parseCommand(topic) ?: return
        val watch = host.watches().firstOrNull { Topics.node(it.id) == node }
        if (watch == null) {
            log("MQTT: a command for a watch that is not paired")
            return
        }
        val target = rest.joinToString("/")
        when {
            rest.size == 2 && rest[0] == "setting" -> changeSetting(watch, rest[1], payload)
            rest == listOf("notify") -> notify(watch, payload)
            rest == listOf("refresh") -> answer(watch.id, target, null, host.command(watch.id, WatchCommand.RefreshValues, Delivery.NextConnection(key = "mqtt.refresh")))
            else -> refuse(watch.id, target, null, "unknown command")
        }
    }

    private suspend fun changeSetting(watch: PairedWatch, key: String, payload: String) {
        val target = "setting/$key"
        val info: SettingInfo = host.settingCatalog(watch.id).firstOrNull { Topics.safe(it.key) == key }
            ?: return refuse(watch.id, target, payload, "not a setting of this watch")
        when (val c = SettingValue.check(info, payload)) {
            is Checked.Refused -> refuse(watch.id, target, payload, c.reason)
            is Checked.Ok -> answer(
                watch.id, target, payload,
                host.command(watch.id, WatchCommand.ChangeSettings(mapOf(info.key to c.value)), Delivery.NextConnection(key = "mqtt.setting.${info.key}")),
            )
        }
    }

    private suspend fun notify(watch: PairedWatch, payload: String) {
        val n = NotifyPayload.parse(payload) ?: return refuse(watch.id, "notify", null, "empty notification")
        // The text is the person's: never in a log line or the result.
        answer(watch.id, "notify", null, host.notify(watch.id, n, Delivery.NextConnection(keepMillis = NOTIFY_KEEP_MILLIS)))
    }

    private fun refuse(watch: WatchId, target: String, value: String?, reason: String) {
        log("MQTT: $target refused: $reason")
        result(watch, target, value, "refused", reason)
    }

    private fun answer(watch: WatchId, target: String, value: String?, r: PluginResult<Delivered>) {
        when (r) {
            is PluginResult.Ok -> result(watch, target, value, if (r.value == Delivered.NOW) "done" else "queued", null)
            is PluginResult.Failed -> {
                log("MQTT: $target failed: ${r.reason.name.lowercase()}")
                result(watch, target, value, "failed", "${r.reason.name.lowercase()}: ${r.message}")
            }
        }
    }

    private fun result(watch: WatchId, target: String, value: String?, status: String, reason: String?) = synchronized(lock) {
        val m = linkedMapOf<String, Any?>("target" to target)
        if (value != null) m["value"] = value.trim().take(MAX_ECHO)
        m["status"] = status
        if (reason != null) m["reason"] = reason
        link.publish(topics.result(watch), Json.write(m), retain = false)
        Unit
    }

    // Publishing

    private fun syncLocked(w: PairedWatch) {
        if (!link.connected) return
        val values = host.lastValues(w.id)
        val catalog = host.settingCatalog(w.id)
        val desired = LinkedHashMap<String, String>()
        if (config.discovery) desired.putAll(discovery.build(w, values, catalog))
        desired[topics.availability(w.id)] = ONLINE
        desired.putAll(states(w, values, catalog))
        val node = Topics.node(w.id)
        val before = loadTopics(node)
        var ok = true
        for (t in before - desired.keys) ok = publish(t, "", retain = true) && ok
        for ((t, p) in desired) ok = publish(t, p, retain = true) && ok
        if (ok && before != desired.keys) saveTopics(node, desired.keys)
        else if (!ok) saveTopics(node, before + desired.keys)
    }

    /** State topic to payload for [w]: its values, then its settings. Absent values publish nothing. */
    internal fun states(w: PairedWatch, values: WatchValues, catalog: List<SettingInfo>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in Entities.valuesFor(w.capabilities)) {
            val v: Any? = if (e.key == "watch.connected") connectedNow[w.id] ?: w.connected else values[e.key]
            Values.format(v)?.let { out[topics.state(w.id, e.obj)] = it }
        }
        val settings = host.settings(w.id).values
        for (s in catalog) {
            val v = settings[s.key] ?: values["watch.setting.${s.key}"]
            SettingValue.format(s, v)?.let { out[topics.settingState(w.id, s.key)] = it }
        }
        return out
    }

    private fun clearNode(node: String) {
        val topicsOf = loadTopics(node)
        var ok = true
        for (t in topicsOf) ok = publish(t, "", retain = true) && ok
        if (ok) host.storage().delete(TOPICS_KEY + node)
    }

    private fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        if (sent[topic] == payload) return true
        if (!link.publish(topic, payload, retain)) return false
        sent[topic] = payload
        return true
    }

    private fun loadTopics(node: String): Set<String> =
        host.storage().get(TOPICS_KEY + node)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private fun saveTopics(node: String, t: Set<String>) {
        if (t.isEmpty()) host.storage().delete(TOPICS_KEY + node) else host.storage().put(TOPICS_KEY + node, t.joinToString("\n"))
    }

    companion object {
        const val ONLINE = "online"
        const val OFFLINE = "offline"

        /** Plugin storage key per watch node: every retained topic published for it, to empty them later. */
        const val TOPICS_KEY = "topics."
        const val MAX_ECHO = 60
        const val NOTIFY_KEEP_MILLIS = 3_600_000L
        const val MAX_QUEUED = 50
        val BUTTON_PAYLOAD: String = Json.write(mapOf("event_type" to Discovery.EVENT_PRESS))
    }
}
