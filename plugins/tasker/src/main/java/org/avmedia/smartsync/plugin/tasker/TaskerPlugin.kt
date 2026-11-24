package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.Control
import org.avmedia.smartsync.plugin.api.DataUse
import org.avmedia.smartsync.plugin.api.PluginAccess
import org.avmedia.smartsync.plugin.api.PluginApi
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginStatus
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchPlugin

/** Where the plugin's events go: Tasker and the other Locale hosts on the phone ([AndroidSignals]), or a test. */
internal interface Signals {
    fun started(host: PluginHost)
    fun stopped()
    fun event(fired: Fired)

    /** A value a state depends on may have changed: the hosts query the states again. */
    fun stateChanged()
}

/**
 * Tasker, MacroDroid and Automate (the Locale plugin contract): watch events and states for their profiles, watch
 * actions for their tasks. No server; nothing leaves the phone.
 */
class TaskerPlugin internal constructor(private val signals: Signals) : WatchPlugin {
    constructor() : this(AndroidSignals)

    override val id = "tasker"
    override val name = "Tasker"
    override val summary = "Watch events and actions for Tasker, MacroDroid and Automate"
    override val description =
        "Lets Tasker and apps like it react to your watch: a button press, a connection, a step goal, a heart rate or " +
            "battery level, a stored workout. Their tasks can send notifications to the watch, change its settings and " +
            "read its values, with the result in variables."
    override val setupNeeds = "Tasker, MacroDroid or Automate"
    override val infoUrl = "https://github.com/ExcuseMi/smartsync-plugins/blob/main/plugins/tasker/docs/tasker.md"
    override val sourceUrl = "https://github.com/ExcuseMi/smartsync-plugins/tree/main/plugins/tasker"
    override val issuesUrl = "https://github.com/ExcuseMi/smartsync-plugins/issues"
    override val author = "ExcuseMi"
    override val license = "Apache-2.0"
    override val apiVersion = PluginApi.VERSION
    override val version = VERSION

    override val access = PluginAccess(
        reads = setOf(DataUse.STEPS, DataUse.HEART_RATE, DataUse.WORKOUTS, DataUse.BATTERY_AND_TEMPERATURE, DataUse.WATCH_INFO),
        controls = setOf(Control.NOTIFICATIONS, Control.WATCH_SETTINGS, Control.WATCH_FACE, Control.BUTTON_PRESS),
        sendsTo = listOf("Tasker and apps on this phone that you set up"),
        stores = listOf("The event, state and action settings you make in those apps (kept by them)"),
        uses = setOf(
            Capability.NOTIFICATIONS, Capability.BATTERY, Capability.STEPS, Capability.HEALTH_LOG, Capability.HEART_RATE,
            Capability.WORKOUTS, Capability.HEALTH_SETTINGS, Capability.WATCH_SETTINGS, Capability.ALARMS, Capability.TIMER,
        ),
    )

    private val mapper = EventMapper()

    @Volatile private var host: PluginHost? = null

    override suspend fun activate(host: PluginHost) {
        this.host = host
        host.declareNeeds(PluginNeeds(values = ValueKeys.ALL, buttonPress = true))
        host.watches().forEach { mapper.seed(host.lastValues(it.id)) }
        signals.started(host)
        host.status(PluginStatus.Ok("Ready for Tasker"))
    }

    override suspend fun deactivate() {
        signals.stopped()
        host = null
    }

    override suspend fun onEvent(event: WatchEvent) {
        val h = host ?: return
        mapper.map(event) { h.watchName(it) }?.let(signals::event)
        when (event) {
            is WatchEvent.Connected, is WatchEvent.Disconnected, is WatchEvent.ValuesUpdated, WatchEvent.EventsLost -> signals.stateChanged()
            else -> Unit
        }
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
