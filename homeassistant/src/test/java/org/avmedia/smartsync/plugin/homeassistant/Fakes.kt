package org.avmedia.smartsync.plugin.homeassistant

import android.content.Context
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.HistoryRecord
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.PluginStatus
import org.avmedia.smartsync.plugin.api.PluginStorage
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.avmedia.smartsync.plugin.api.SettingsSnapshot
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchNotification
import org.avmedia.smartsync.plugin.api.WatchValues
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import java.time.Instant
import java.time.OffsetDateTime

class MemStorage : PluginStorage {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A host as the app gives it, without a watch or Android: capabilities, catalogue and values are set by the test. */
class FakeHost : PluginHost {
    override val context: Context get() = error("no Android context in JVM tests")
    override val appVersion = "test"
    override val apiVersion = "0.1.0"

    val paired = mutableListOf<PairedWatch>()
    val values = HashMap<WatchId, MutableMap<String, Any?>>()
    val settingValues = HashMap<WatchId, MutableMap<String, Any?>>()
    val catalogs = HashMap<WatchId, List<SettingInfo>>()
    val commands = mutableListOf<Triple<WatchId, WatchCommand, Delivery>>()
    val notifications = mutableListOf<Pair<WatchId, WatchNotification>>()
    val statuses = mutableListOf<PluginStatus>()
    var answer: PluginResult<Delivered> = PluginResult.Ok(Delivered.NOW)
    val plugin = MemStorage()
    private val perWatch = HashMap<WatchId, MemStorage>()
    var needs: PluginNeeds? = null

    fun add(key: String, name: String, caps: Set<Capability>, catalog: List<SettingInfo>, connected: Boolean = true): WatchId {
        val id = WatchId(key)
        paired += PairedWatch(id, name, caps, connected)
        values[id] = LinkedHashMap()
        settingValues[id] = LinkedHashMap()
        catalogs[id] = catalog
        return id
    }

    fun setCaps(id: WatchId, caps: Set<Capability>) {
        val i = paired.indexOfFirst { it.id == id }
        paired[i] = paired[i].copy(capabilities = caps)
    }

    override fun watches(): List<PairedWatch> = paired.toList()
    override fun connected(): WatchId? = paired.firstOrNull { it.connected }?.id
    override fun current(): WatchId? = connected() ?: paired.firstOrNull()?.id
    override fun watchName(watch: WatchId): String? = paired.firstOrNull { it.id == watch }?.name

    override suspend fun notify(watch: WatchId, notification: WatchNotification, delivery: Delivery): PluginResult<Delivered> {
        notifications += watch to notification
        return answer
    }

    override suspend fun command(watch: WatchId, command: WatchCommand, delivery: Delivery): PluginResult<Delivered> {
        commands += Triple(watch, command, delivery)
        return answer
    }

    override fun valueKeys(): List<String> = Entities.VALUE_KEYS.toList() + "watch.setting.watch_face"
    override fun lastValues(watch: WatchId) = WatchValues(watch, values[watch].orEmpty().toMap())
    override fun settings(watch: WatchId) = SettingsSnapshot(watch, settingValues[watch].orEmpty().toMap(), 1L)
    override fun settingCatalog(watch: WatchId): List<SettingInfo> = catalogs[watch].orEmpty()

    val hours = HashMap<WatchId, List<HourRecord>>()
    val heartRate = HashMap<WatchId, List<HeartRateSample>>()
    val workouts = HashMap<WatchId, List<WorkoutRecord>>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : HistoryRecord> history(watch: WatchId, kind: DataKind<T>, range: HistoryRange): PluginResult<List<T>> {
        val all: List<HistoryRecord> = when (kind) {
            DataKind.Hours -> hours[watch].orEmpty().filter { range.contains(it.start) }
            DataKind.HeartRate -> heartRate[watch].orEmpty().filter { range.contains(it.time) }
            DataKind.Workouts -> workouts[watch].orEmpty().filter { range.contains(java.time.LocalDateTime.ofInstant(it.start, java.time.ZoneId.of("Europe/Brussels"))) }
            else -> emptyList()
        }
        return PluginResult.Ok(all as List<T>)
    }

    override fun storage(): PluginStorage = plugin
    override fun storage(watch: WatchId): PluginStorage = perWatch.getOrPut(watch) { MemStorage() }
    override fun moveWatchData(from: WatchId, to: WatchId) {}
    override fun declareNeeds(needs: PluginNeeds) { this.needs = needs }
    override fun status(status: PluginStatus) { statuses += status }

    fun remove(id: WatchId) {
        paired.removeAll { it.id == id }
        perWatch.remove(id)
    }
}

/** A broker connection that records what is published. */
class FakeLink(override var connected: Boolean = true, override val hasWill: Boolean = true) : MqttLink {
    data class Msg(val topic: String, val payload: String, val retain: Boolean)

    val published = mutableListOf<Msg>()
    val subscriptions = mutableListOf<String>()
    var listener: MqttLink.Listener? = null
    var config: BrokerConfig? = null
    var will: Will? = null
    var goodbye: Will? = null

    override fun connect(config: BrokerConfig, will: Will, listener: MqttLink.Listener) {
        this.config = config
        this.will = will
        this.listener = listener
    }

    override fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        if (!connected) return false
        published += Msg(topic, payload, retain)
        return true
    }

    override fun subscribe(filter: String): Boolean { subscriptions += filter; return connected }

    override fun disconnect(goodbye: Will?) { this.goodbye = goodbye; connected = false }

    fun last(topic: String): Msg? = published.lastOrNull { it.topic == topic }
    fun configs(): Map<String, String> = published.filter { it.topic.endsWith("/config") }.associate { it.topic to it.payload }
}

object Watches {
    val GBX_CAPS = setOf(
        Capability.ALWAYS_CONNECTED, Capability.NOTIFICATIONS, Capability.BATTERY, Capability.TEMPERATURE, Capability.STEPS,
        Capability.HEALTH_LOG, Capability.HEART_RATE, Capability.SLEEP, Capability.BLOOD_OXYGEN, Capability.WORKOUTS,
        Capability.HEALTH_SETTINGS, Capability.FIRMWARE_INFO, Capability.HOME_TIME, Capability.TIMER, Capability.ALARMS,
        Capability.WATCH_SETTINGS,
    )

    /** An ocean watch without health: battery, temperature, home time and its settings. */
    val OCW_CAPS = setOf(Capability.BATTERY, Capability.TEMPERATURE, Capability.HOME_TIME, Capability.WORLD_CITIES)

    val GBX_CATALOG = listOf(
        SettingInfo("watch_face", SettingType.CHOICE, choices = listOf("Activity", "Heart rate", "Tide")),
        SettingInfo("auto_light", SettingType.BOOL),
        SettingInfo("light_duration", SettingType.WHOLE, range = 1..5, only = setOf(1, 3)),
        SettingInfo("step_goal", SettingType.WHOLE, range = 1000..50000),
        SettingInfo("bedtime", SettingType.TIME),
    )

    val OCW_CATALOG = listOf(SettingInfo("button_tone", SettingType.BOOL))

    const val CONFIG_PASSWORD = "s3cr3t-Pa55word"

    fun config() = BrokerConfig(mode = ConnectMode.DIRECT, host = "broker.lan", port = 1883, username = "smartsync", password = CONFIG_PASSWORD, clientId = "smartsync-test")
}

/** Home Assistant's websocket API: records what is sent, keeps statistics rows by hour, knows the plugin's entities. */
class FakeChannel(var isReady: Boolean = true) : HaChannel {
    override val ready: Boolean get() = isReady
    val calls = mutableListOf<Map<String, Any?>>()
    val sent = mutableListOf<Map<String, Any?>>()
    val subs = mutableListOf<Pair<Map<String, Any?>, (Map<String, Any?>) -> Unit>>()

    /** Statistic id to hour start to its row (sum or mean). */
    val stats = HashMap<String, MutableMap<Instant, Map<String, Any?>>>()

    /** The entity registry: unique id to entity id, all from the mqtt platform. */
    val entities = LinkedHashMap<String, String>()

    fun imports() = calls.filter { it["type"] == "recorder/import_statistics" }

    @Suppress("UNCHECKED_CAST")
    override suspend fun call(message: Map<String, Any?>): HaReply {
        calls += message
        return when (message["type"]) {
            "config/entity_registry/list" ->
                HaReply(true, entities.map { (u, e) -> mapOf("entity_id" to e, "unique_id" to u, "platform" to "mqtt") } + mapOf("entity_id" to "light.x", "unique_id" to "x", "platform" to "hue"))
            "recorder/statistics_during_period" -> {
                val ids = message["statistic_ids"] as List<String>
                HaReply(true, ids.filter { it in stats }.associateWith { id ->
                    stats.getValue(id).toSortedMap().map { (t, r) -> mapOf("start" to t.toEpochMilli().toDouble()) + r }
                })
            }
            "recorder/import_statistics" -> {
                val id = (message["metadata"] as Map<String, Any?>)["statistic_id"] as String
                (message["stats"] as List<Map<String, Any?>>).forEach {
                    stats.getOrPut(id) { mutableMapOf() }[OffsetDateTime.parse(it["start"] as String).toInstant()] = it - "start"
                }
                HaReply(true)
            }
            else -> HaReply.failed("unknown_command", "test")
        }
    }

    override fun send(message: Map<String, Any?>): Boolean { sent += message; return isReady }

    override fun subscribe(message: Map<String, Any?>, onEvent: (Map<String, Any?>) -> Unit): Boolean {
        subs += message to onEvent
        return isReady
    }
}
