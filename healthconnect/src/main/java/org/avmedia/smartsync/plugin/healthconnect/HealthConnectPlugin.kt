package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataUse
import org.avmedia.smartsync.plugin.api.PluginAccess
import org.avmedia.smartsync.plugin.api.PluginApi
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginUi
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchPlugin
import org.avmedia.smartsync.plugin.healthconnect.ui.HealthConnectUi

/** Writes the watch's health data to Android Health Connect. Off until the person turns it on in Settings. */
class HealthConnectPlugin internal constructor(private val sinkFor: (PluginHost) -> HealthSink) : WatchPlugin {

    constructor() : this({ HealthConnectSink(it.context) })

    override val id = ID
    override val name = "Health Connect"
    override val summary = "Writes the watch's steps, heart rate, sleep and workouts to Health Connect."
    override val apiVersion = PluginApi.VERSION
    override val version = VERSION
    override val access = ACCESS

    @Volatile internal var host: PluginHost? = null
        private set
    @Volatile internal var sink: HealthSink? = null
        private set
    @Volatile private var writer: HealthWriter? = null

    override suspend fun activate(host: PluginHost) {
        val sink = sinkFor(host)
        this.host = host
        this.sink = sink
        writer = HealthWriter(host, sink)
        host.declareNeeds(PluginNeeds(healthReads = true))
    }

    override suspend fun deactivate() {
        writer = null
        sink = null
        host = null
    }

    override suspend fun onEvent(event: WatchEvent) {
        val w = writer ?: return
        when (event) {
            is WatchEvent.HealthRead -> w.healthRead(event.watch, event.range, event.kinds)
            is WatchEvent.WorkoutStored -> w.workoutStored(event.watch, event.workout)
            else -> Unit
        }
    }

    override val ui: PluginUi by lazy { HealthConnectUi(this) }

    companion object {
        const val ID = "health_connect"

        /** The plugin's version (healthconnect/build.gradle, tag healthconnect-v<version>). */
        const val VERSION = "0.1.0"

        val ACCESS = PluginAccess(
            reads = setOf(DataUse.STEPS, DataUse.HEART_RATE, DataUse.SLEEP, DataUse.BLOOD_OXYGEN, DataUse.WORKOUTS),
            sendsTo = listOf("Health Connect on this phone"),
            stores = listOf("which records it wrote, so none is written twice"),
            requires = setOf(Capability.HEALTH_LOG),
            uses = setOf(Capability.STEPS, Capability.HEART_RATE, Capability.SLEEP, Capability.BLOOD_OXYGEN, Capability.WORKOUTS),
        )
    }
}
