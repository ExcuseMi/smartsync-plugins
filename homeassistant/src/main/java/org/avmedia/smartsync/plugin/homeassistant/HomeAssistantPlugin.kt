package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) : WatchPlugin {
    override val id = ID
    override val name = NAME
    override val apiVersion = PluginApi.VERSION
    override val version = VERSION
    override val access = ACCESS
    override val icon: PluginIcon get() = PluginIcon.Vector(HaIcon.image)

    @Volatile private var host: PluginHost? = null
    @Volatile private var bridge: Bridge? = null
    @Volatile private var link: MqttLink? = null
    @Volatile private var scope: CoroutineScope? = null
    private val runLock = Mutex()

    private val _status = MutableStateFlow(NOT_SET_UP)

    /** One line for the settings card. */
    val status: StateFlow<String> get() = _status

    override suspend fun activate(host: PluginHost) {
        this.host = host
        val settingKeys = host.valueKeys().filter { it.startsWith("watch.setting.") }
        host.declareNeeds(PluginNeeds(values = Entities.VALUE_KEYS + settingKeys, buttonPress = true, healthReads = true))
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
            is WatchEvent.Connected -> b.connectionChanged(event.watch, true)
            is WatchEvent.Disconnected -> b.connectionChanged(event.watch, false)
            is WatchEvent.ValuesUpdated -> b.sync(event.watch)
            is WatchEvent.SettingChanged -> b.sync(event.watch)
            is WatchEvent.Condition -> b.sync(event.watch)
            is WatchEvent.HealthRead -> b.sync(event.watch)
            is WatchEvent.WorkoutStored -> b.sync(event.watch)
            is WatchEvent.ButtonPressed -> b.buttonPressed(event.watch)
            is WatchEvent.WatchRemoved -> b.removeWatch(event.watch)
            else -> Unit
        }
    }

    /** After the settings were saved: a new connection with them. */
    fun restart() {
        scope?.launch { runLock.withLock { stop(); start() } }
    }

    internal fun host(): PluginHost? = host

    private fun start() {
        val h = host ?: return
        val cfg = BrokerConfig.load(h.storage())
        val problem = cfg.problem()
        if (problem != null) {
            _status.value = if (cfg.host.isBlank()) NOT_SET_UP else "Not set up: $problem"
            h.status(PluginStatus.Ok(NOT_SET_UP))
            return
        }
        val l = linkFactory()
        val b = Bridge(h, l, cfg, VERSION, logger)
        link = l
        bridge = b
        logger("MQTT: connecting to ${Redact.url(cfg.serverUri())}")
        _status.value = "Connecting to ${cfg.host}"
        l.connect(cfg, b.will, object : MqttLink.Listener {
            override fun onConnected() {
                if (bridge !== b) return
                _status.value = "Connected to ${cfg.host}"
                h.status(PluginStatus.Ok("Connected"))
                logger("MQTT: connected")
                scope?.launch { b.onConnected() }
            }

            override fun onConnectionLost(reason: String) {
                if (bridge !== b) return
                val r = cfg.scrub(reason)
                _status.value = "Not connected: $r"
                h.status(PluginStatus.Error("Broker not reachable: $r"))
                logger("MQTT: not connected: $r")
            }

            override fun onMessage(topic: String, payload: String) {
                if (bridge !== b) return
                scope?.launch { b.handle(topic, payload) }
            }
        })
    }

    private fun stop() {
        val l = link
        val b = bridge
        link = null
        bridge = null
        if (l != null && b != null) l.disconnect(b.will)
    }

    companion object {
        const val ID = "home_assistant_mqtt"
        const val NAME = "Home Assistant"
        const val SUMMARY = "Your watch in Home Assistant, with live values, real controls and history."

        /** The plugin's version (homeassistant/build.gradle, tag v<version>). */
        const val VERSION = "0.1.0"
        const val NOT_SET_UP = "Not set up"

        val ACCESS = PluginAccess(
            reads = setOf(
                DataUse.STEPS, DataUse.HEART_RATE, DataUse.SLEEP, DataUse.BLOOD_OXYGEN, DataUse.WORKOUTS,
                DataUse.BATTERY_AND_TEMPERATURE, DataUse.WATCH_SETTINGS, DataUse.WATCH_INFO, DataUse.PHONE,
            ),
            controls = setOf(Control.NOTIFICATIONS, Control.WATCH_SETTINGS, Control.WATCH_FACE, Control.BUTTON_PRESS),
            sendsTo = listOf("your MQTT broker"),
            stores = listOf("MQTT broker address, user name and password"),
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
