package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues
import java.time.LocalDate
import java.time.ZoneId

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
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = LocalDate::now,
    /** Runs the block after the delay in ms, off the caller's thread (the plugin's scope); tests run it by hand. */
    private val later: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val log: (String) -> Unit,
) {
    val topics = Topics(config.baseTopic, config.discoveryPrefix)

    /**
     * No expire_after: a sensor with it starts unavailable and is set unavailable again by every change of its config,
     * and a retained state does not undo that (2026-11-24: 38 sensors unavailable after a plugin update). A value the
     * watch never gave shows unknown; the app's absence shows through the availability topics.
     */
    private val discovery = Discovery(topics, pluginVersion, health = config.shareHealth)
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
        // Home Assistant says "online" on its restart: discovery and states go out again then.
        if (config.discovery && !link.subscribe(topics.haStatus)) log("MQTT: could not subscribe to the Home Assistant status")
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

    /** Home Assistant restarted (or lost its retained configs): every config and state again. */
    fun rediscover() = synchronized(lock) {
        sent.clear()
        publish(topics.bridgeAvailability, ONLINE, retain = true)
        host.watches().forEach { syncLocked(it) }
    }

    /** States and availability again although unchanged: repairs anything Home Assistant lost while the app runs. */
    fun heartbeat() = synchronized(lock) {
        sent.keys.removeAll { !it.endsWith("/config") }
        publish(topics.bridgeAvailability, ONLINE, retain = true)
        host.watches().forEach { syncLocked(it) }
    }

    /**
     * [watch]'s states again although unchanged. Runs [ANNOUNCE_DELAY_MS] after its discovery configs changed: Home
     * Assistant sets up or updates the entities from them first, and a state sent before that is lost to them.
     */
    fun announce(watch: WatchId) = synchronized(lock) {
        val root = topics.availability(watch).removeSuffix("availability")
        sent.keys.removeAll { it.startsWith(root) }
        host.watches().firstOrNull { it.id == watch }?.let { syncLocked(it) }
        Unit
    }

    /** A planned stop: the bridge and every watch go offline (through Home Assistant there is no last will to do it). */
    fun goingOffline() = synchronized(lock) {
        if (link.connected) {
            host.watches().forEach { link.publish(topics.availability(it.id), OFFLINE, retain = true) }
            link.publish(topics.bridgeAvailability, OFFLINE, retain = true)
        }
        sent.clear()
    }

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

    /** [from] re-paired as [to]: Home Assistant drops the device of the old id and gets the watch under the new one. */
    fun rePaired(from: WatchId, to: WatchId) = synchronized(lock) {
        connectedNow.remove(from)
        clearNode(Topics.node(from))
        host.watches().firstOrNull { it.id == to }?.let { syncLocked(it) }
        Unit
    }

    // Commands

    suspend fun handle(topic: String, payload: String) {
        if (topic == topics.haStatus) {
            if (payload.trim().equals(ONLINE, ignoreCase = true)) rediscover()
            return
        }
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
        // A control shows the watch's real value again after a refused or failed change (a written one is read back).
        val last = lastResult[watch.id]
        if (last == "refused" || last == "failed") restoreState(watch.id, info.key)
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

    private val lastResult = HashMap<WatchId, String>()

    private fun restoreState(watch: WatchId, key: String) = synchronized(lock) {
        sent.remove(topics.settingState(watch, key))
        host.watches().firstOrNull { it.id == watch }?.let { syncLocked(it) }
        Unit
    }

    private fun result(watch: WatchId, target: String, value: String?, status: String, reason: String?) = synchronized(lock) {
        lastResult[watch] = status
        if (status == "done" || status == "queued") log("MQTT: $target $status")
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
        desired[topics.availability(w.id)] = if (online(w)) ONLINE else OFFLINE
        desired.putAll(states(w, values, catalog))
        val node = Topics.node(w.id)
        val before = loadTopics(node)
        // A value missing now (not read yet since an app restart) keeps its retained last value: only the topics of
        // entities the watch no longer has are emptied.
        val kept = before.filter { it in stateTopics(w, catalog) && it !in desired.keys }
        val wanted = desired.keys + kept
        var ok = true
        for (t in before - wanted) ok = publish(t, "", retain = true) && ok
        var configChanged = false
        for ((t, p) in desired) {
            if (t.endsWith("/config") && sent[t] != p) configChanged = true
            ok = publish(t, p, retain = true) && ok
        }
        if (ok && before != wanted) saveTopics(node, wanted)
        else if (!ok) saveTopics(node, before + wanted)
        if (configChanged) later(ANNOUNCE_DELAY_MS) { announce(w.id) }
    }

    /** Every state topic [w] has an entity for, with or without a value now. */
    private fun stateTopics(w: PairedWatch, catalog: List<SettingInfo>): Set<String> =
        Entities.valuesFor(w.capabilities, config.shareHealth).map { topics.state(w.id, it.obj) }.toSet() +
            catalog.map { topics.settingState(w.id, it.key) }

    /**
     * A watch that keeps its link up (ALWAYS_CONNECTED) is unavailable while disconnected; one that connects by its
     * button stays available with its last values (it is away most of the day by design).
     */
    private fun online(w: PairedWatch): Boolean =
        Capability.ALWAYS_CONNECTED !in w.capabilities || (connectedNow[w.id] ?: w.connected)

    /** State topic to payload for [w]: its values, then its settings. Absent values publish nothing. */
    internal fun states(w: PairedWatch, values: WatchValues, catalog: List<SettingInfo>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val types = host.valueCatalog().associate { it.key to it.type }
        for (e in Entities.valuesFor(w.capabilities, config.shareHealth)) {
            val v: Any? = if (e.key == "watch.connected") connectedNow[w.id] ?: w.connected else Entities.convert(e, values[e.key], zone(), types[e.key])
            Values.format(v)?.let { out[topics.state(w.id, e.obj)] = it }
        }
        val settings = host.settings(w.id).values
        for (s in catalog) {
            SettingValue.format(s, settings[s.key])?.let { out[topics.settingState(w.id, s.key)] = it }
        }
        return out
    }

    private fun clearNode(node: String) {
        val topicsOf = loadTopics(node) + loadHistory(node).keys
        var ok = true
        for (t in topicsOf) ok = publish(t, "", retain = true) && ok
        if (ok) {
            host.storage().delete(TOPICS_KEY + node)
            host.storage().delete(HISTORY_KEY + node)
        }
    }

    // History topics (HistoryTopics): the last days, each published again only when it changed.

    suspend fun syncHistory(watch: PairedWatch) {
        if (Capability.HEALTH_LOG !in watch.capabilities && Capability.WORKOUTS !in watch.capabilities) return
        val w = watch.id
        val range = HistoryRange.days(HistoryTopics.firstDay(today()), today())
        // Health kept out of Home Assistant: nothing is built, so the topics published before are emptied below.
        val built = if (!config.shareHealth) emptyMap() else HistoryTopics.build(
            topics, w,
            host.history(w, DataKind.Hours, range).valueOrNull().orEmpty(),
            host.history(w, DataKind.HeartRate, range).valueOrNull().orEmpty(),
            host.history(w, DataKind.Workouts, range).valueOrNull().orEmpty(),
            zone(),
        )
        synchronized(lock) {
            if (!link.connected) return
            val node = Topics.node(w)
            val stored = loadHistory(node)
            val next = LinkedHashMap<String, String>()
            for ((t, p) in built) {
                val h = Integer.toHexString(p.hashCode())
                when {
                    stored[t] == h -> next[t] = h
                    link.publish(t, p, retain = true) -> next[t] = h
                    else -> stored[t]?.let { next[t] = it }
                }
            }
            // Days (and workouts) older than the kept days are emptied, so the broker keeps a bounded history.
            for ((t, h) in stored) if (t !in built && !link.publish(t, "", retain = true)) next[t] = h
            if (next.isEmpty()) host.storage().delete(HISTORY_KEY + node)
            else host.storage().put(HISTORY_KEY + node, next.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    private fun loadHistory(node: String): Map<String, String> =
        host.storage().get(HISTORY_KEY + node)?.split('\n')?.mapNotNull { l ->
            val i = l.lastIndexOf('\t')
            if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }?.toMap().orEmpty()

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

        /** Plugin storage key per watch node: the history topics published, with a hash of each payload. */
        const val HISTORY_KEY = "history."

        /** Through Home Assistant: every state again this often ([heartbeat]). */
        const val HEARTBEAT_MIN = 30L

        /** How long Home Assistant gets to set up the entities of new or changed configs before the states follow. */
        const val ANNOUNCE_DELAY_MS = 5_000L
        const val MAX_ECHO = 60
        const val NOTIFY_KEEP_MILLIS = 3_600_000L
        const val MAX_QUEUED = 50
        val BUTTON_PAYLOAD: String = Json.write(mapOf("event_type" to Discovery.EVENT_PRESS))
    }
}
