package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.Control
import org.avmedia.smartsync.plugin.api.DataUse
import org.avmedia.smartsync.plugin.api.PluginAccess
import org.avmedia.smartsync.plugin.api.PluginApi
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginIcon
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginStatus
import org.avmedia.smartsync.plugin.api.PluginUi
import org.avmedia.smartsync.plugin.api.Redact
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchPlugin
import timber.log.Timber

/**
 * Publishes every paired watch's values and controls to an MQTT broker (one connection for all watches), with Home
 * Assistant MQTT discovery, and runs the commands that come back. Events only mark what changed; publishing never
 * holds the watch.
 */
class HomeAssistantPlugin(
    private val linkFactory: () -> MqttLink = { PahoLink() },
    private val logger: (String) -> Unit = { Timber.tag("home_assistant_mqtt").i(it) },
    private val channelFactory: (BrokerConfig, HaChannel.Listener) -> HaChannel = { c, l -> HaSocket(c, l) },
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchPlugin {
    override val id = ID
    override val name = NAME
    override val summary = SUMMARY
    override val description = DESCRIPTION
    override val setupNeeds = "Home Assistant with MQTT, its address and an administrator's token"
    override val infoUrl = "$REPO/blob/main/docs/homeassistant.md"
    override val sourceUrl = "$REPO/tree/main/homeassistant"
    override val issuesUrl = "$REPO/issues"
    override val author = "ExcuseMi"
    override val license = "Apache-2.0"
    override val apiVersion = PluginApi.VERSION
    override val version = VERSION
    override val access = ACCESS
    override val icon: PluginIcon get() = PluginIcon.Vector(HaIcon.image)

    @Volatile private var host: PluginHost? = null
    @Volatile private var bridge: Bridge? = null
    @Volatile private var link: MqttLink? = null
    @Volatile private var channel: HaChannel? = null
    @Volatile private var stats: StatsSync? = null
    @Volatile private var scope: CoroutineScope? = null
    private val runLock = Mutex()

    /** When the gap check of the statistics last ran (each connection, and at a [WatchEvent.Wake] a day later). */
    @Volatile private var historyAt = 0L

    private val _status = MutableStateFlow(NOT_SET_UP)
    private val dashboards = DashboardSync { channel }
    private val _dashboardStatus = MutableStateFlow<Map<WatchId, String>>(emptyMap())

    /** The last dashboard result per watch, for the settings screen. */
    val dashboardStatus: StateFlow<Map<WatchId, String>> get() = _dashboardStatus

    /** One line for the settings card. */
    val status: StateFlow<String> get() = _status

    override suspend fun activate(host: PluginHost) {
        this.host = host
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runLock.withLock { start() }
    }

    override suspend fun deactivate() {
        runLock.withLock { stop() }
        scope?.cancel()
        scope = null
        host = null
    }

    override suspend fun onEvent(event: WatchEvent) {
        val b = bridge ?: return
        when (event) {
            is WatchEvent.Connected -> { b.connectionChanged(event.watch, true); dashboardSoon(event.watch) }
            is WatchEvent.Disconnected -> b.connectionChanged(event.watch, false)
            is WatchEvent.ValuesUpdated -> b.sync(event.watch)
            is WatchEvent.SettingChanged -> b.sync(event.watch)
            is WatchEvent.HealthRead -> { b.sync(event.watch); history(event.watch, StatsSync.GAP_DAYS) }
            is WatchEvent.WorkoutStored -> { b.sync(event.watch); history(event.watch, null) }
            is WatchEvent.ButtonPressed -> b.buttonPressed(event.watch)
            is WatchEvent.WatchRemoved -> { b.removeWatch(event.watch); scope?.launch { dashboards.remove(event.watch) } }
            is WatchEvent.WatchRePaired -> { b.rePaired(event.from, event.watch); scope?.launch { dashboards.remove(event.from) }; dashboardSoon(event.watch) }
            is WatchEvent.Wake -> wake(b)
            else -> Unit
        }
    }

    /**
     * The host's periodic wake-up ([PluginNeeds.wakeEveryMinutes]), also in a process the app was stopped in: through
     * Home Assistant the states again before the sensors expire, and the daily gap check of the statistics. Runs to the
     * end here: the host keeps the process until this returns.
     */
    private suspend fun wake(b: Bridge) {
        if (link?.hasWill == false) b.heartbeat()
        val st = stats ?: return
        if (clock() - historyAt < DAY_MILLIS) return
        historyAt = clock()
        host?.watches()?.forEach { pw ->
            b.syncHistory(pw)
            st.sync(pw, StatsSync.GAP_DAYS)
        }
    }

    /** "Share health data with Home Assistant" as saved; on until switched off. */
    fun shareHealth(): Boolean = host?.storage()?.get(BrokerConfig.KEY_SHARE_HEALTH) != "false"

    /** Turns health data in Home Assistant on or off: saved, then a new connection without (or with) the health entities. */
    fun setShareHealth(on: Boolean) {
        val h = host ?: return
        scope?.launch {
            h.storage().put(BrokerConfig.KEY_SHARE_HEALTH, on.toString())
            runLock.withLock { stop(); start() }
        }
    }

    /** After the settings were saved: a new connection with them. */
    fun restart() {
        scope?.launch { runLock.withLock { stop(); start() } }
    }

    internal fun host(): PluginHost? = host

    /** Whether the plugin keeps a dashboard for [watch] ("Create a dashboard for this watch"). */
    fun dashboardWanted(watch: WatchId): Boolean = host?.storage(watch)?.get(DASHBOARD_KEY) == ON

    /** Turns the dashboard of [watch] on (made now, kept up to date) or off (its own dashboard removed). */
    fun setDashboard(watch: WatchId, on: Boolean) {
        val h = host ?: return
        scope?.launch {
            if (on) h.storage(watch).put(DASHBOARD_KEY, ON) else h.storage(watch).delete(DASHBOARD_KEY)
            val r = if (on) h.watches().firstOrNull { it.id == watch }?.let { dashboards.sync(it, stepGoal(watch)) } else dashboards.remove(watch)
            r?.let { dashboardDone(watch, it) }
        }
    }

    /** The dashboard of [watch] as YAML for the raw configuration editor, to [onDone] on the main thread's next frame. */
    fun dashboardYaml(watch: WatchId, onDone: (DashboardResult) -> Unit) {
        val h = host ?: return
        val pw = h.watches().firstOrNull { it.id == watch } ?: return
        scope?.launch { onDone(dashboards.yaml(pw, h.settingCatalog(watch), stepGoal(watch))) }
    }

    /** After discovery had time to reach Home Assistant: the dashboard of [watch] (when wanted) follows its entities. */
    private fun dashboardSoon(watch: WatchId? = null) {
        val h = host ?: return
        scope?.launch {
            delay(DASHBOARD_DELAY_MS)
            h.watches().filter { (watch == null || it.id == watch) && dashboardWanted(it.id) }
                .forEach { dashboardDone(it.id, dashboards.sync(it, stepGoal(it.id))) }
        }
    }

    private fun dashboardDone(watch: WatchId, r: DashboardResult) {
        logger("Home Assistant dashboard: ${r.message}")
        _dashboardStatus.value = _dashboardStatus.value + (watch to r.message)
    }

    private fun stepGoal(watch: WatchId): Int? = (host?.lastValues(watch)?.get("health.step_goal") as? Number)?.toInt()

    /** History topics and, with the Home Assistant API, statistics for [watch]; [statDays] null: topics only. */
    private fun history(watch: WatchId, statDays: Long?) {
        val h = host ?: return
        val pw = h.watches().firstOrNull { it.id == watch } ?: return
        val b = bridge ?: return
        val st = stats
        scope?.launch {
            b.syncHistory(pw)
            if (statDays != null) st?.sync(pw, statDays)
        }
    }

    private fun historyAll() {
        historyAt = clock()
        host?.watches()?.forEach { history(it.id, StatsSync.GAP_DAYS) }
    }

    private fun start() {
        val h = host ?: return
        val sc = scope ?: return
        val cfg = BrokerConfig.load(h.storage())
        val problem = cfg.problem()
        h.declareNeeds(if (problem != null) PluginNeeds() else needs(cfg))
        if (problem != null) {
            _status.value = if (cfg.haUrl.isBlank() && cfg.host.isBlank()) NOT_SET_UP else "Not set up: $problem"
            h.status(PluginStatus.Ok(NOT_SET_UP))
            return
        }
        val through = cfg.mode == ConnectMode.THROUGH_HA
        var haLink: HaLink? = null
        val ch: HaChannel? = if (through || cfg.hasHaApi) channelFactory(cfg, object : HaChannel.Listener {
            override fun onReady() {
                logger("Home Assistant: connected")
                if (through) haLink?.ready() else historyAll()
                dashboardSoon()
            }

            override fun onLost(reason: String, authRefused: Boolean) {
                val r = cfg.scrub(reason)
                logger("Home Assistant: not connected: $r")
                if (authRefused) h.status(PluginStatus.Error(r))
                if (through) haLink?.lost(r)
            }
        }) else null
        val l: MqttLink = if (through) HaLink { channel }.also { haLink = it } else linkFactory()
        val b = Bridge(h, l, cfg, VERSION, log = logger)
        channel = ch
        link = l
        bridge = b
        stats = if (ch != null && cfg.shareHealth) StatsSync(h, { channel }, logger) else null
        val where = if (through) Redact.url(cfg.haUrl) else Redact.url(cfg.serverUri())
        logger("MQTT: connecting to $where")
        _status.value = "Connecting to " + if (through) "Home Assistant" else cfg.host
        l.connect(cfg, b.will, object : MqttLink.Listener {
            override fun onConnected() {
                if (bridge !== b) return
                _status.value = if (through) "Connected through Home Assistant" else "Connected to ${cfg.host}"
                h.status(PluginStatus.Ok("Connected"))
                logger("MQTT: connected")
                sc.launch {
                    b.onConnected()
                    historyAll()
                }
            }

            override fun onConnectionLost(reason: String) {
                if (bridge !== b) return
                val r = cfg.scrub(reason)
                _status.value = "Not connected: $r"
                h.status(PluginStatus.Error("Not connected: $r"))
                logger("MQTT: not connected: $r")
            }

            override fun onMessage(topic: String, payload: String) {
                if (bridge !== b) return
                sc.launch { b.handle(topic, payload) }
            }
        })
        ch?.start()
    }

    private fun stop() {
        val l = link
        val b = bridge
        val ch = channel
        link = null
        bridge = null
        stats = null
        if (l != null && b != null) {
            b.goingOffline()
            l.disconnect(if (l.hasWill) b.will else null)
        }
        ch?.stop()
        channel = null
    }

    companion object {
        const val ID = "home_assistant_mqtt"
        const val NAME = "Home Assistant"
        const val SUMMARY = "Your watch in Home Assistant, with live values, real controls and history."
        const val DESCRIPTION = "Adds each watch as a device in Home Assistant: battery, steps, heart rate, sleep and " +
            "workouts as sensors, the watch's settings as controls. Send notifications to the watch and use its button " +
            "in automations. Hourly history goes into Home Assistant's long-term statistics."
        const val REPO = "https://github.com/ExcuseMi/smartsync-plugins"

        /** The plugin's version (homeassistant/build.gradle, tag v<version>). */
        const val VERSION = "0.1.1"
        const val NOT_SET_UP = "Not set up"
        const val DAY_MILLIS = 24 * 3_600_000L

        /** Per-watch storage key: "on" while the plugin keeps a dashboard for the watch. */
        const val DASHBOARD_KEY = "dashboard"
        const val ON = "on"
        const val DASHBOARD_DELAY_MS = 15_000L

        /** Through Home Assistant the sensors expire without a message: woken in time to send them again. */
        const val HEARTBEAT_WAKE_MIN = Bridge.HEARTBEAT_MIN.toInt()

        /** Direct to a broker with the Home Assistant API: woken a few times a day for the daily gap check. */
        const val GAP_WAKE_MIN = 360

        /** What the host reads and sends for [cfg]: settings always, health only while it is shared. */
        fun needs(cfg: BrokerConfig) = PluginNeeds(
            values = if (cfg.shareHealth) Entities.VALUE_KEYS else Entities.VALUE_KEYS_WITHOUT_HEALTH,
            settings = true,
            buttonPress = true,
            healthReads = cfg.shareHealth,
            wakeEveryMinutes = when {
                cfg.mode == ConnectMode.THROUGH_HA -> HEARTBEAT_WAKE_MIN
                cfg.hasHaApi && cfg.shareHealth -> GAP_WAKE_MIN
                else -> null
            },
        )

        /** Everything the watch and phone give but the phone's location, which no entity uses; every control. */
        val ACCESS = PluginAccess(
            reads = DataUse.ALL - DataUse.PHONE_LOCATION,
            controls = Control.ALL,
            sendsTo = listOf("your MQTT broker", "your Home Assistant (statistics, and a dashboard when you ask for one)"),
            stores = listOf("MQTT broker address, user name and password", "Home Assistant address and token"),
            uses = setOf(
                Capability.BATTERY, Capability.TEMPERATURE, Capability.STEPS, Capability.HEALTH_LOG, Capability.HEART_RATE,
                Capability.SLEEP, Capability.BLOOD_OXYGEN, Capability.WORKOUTS, Capability.NOTIFICATIONS,
                Capability.WATCH_SETTINGS, Capability.HEALTH_SETTINGS, Capability.FIRMWARE_INFO, Capability.HOME_TIME,
                Capability.TIMER,
            ),
        )
    }

    override val ui: PluginUi by lazy { HaUi(this) }
}
