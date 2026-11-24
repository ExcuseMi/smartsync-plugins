package org.avmedia.smartsync.plugin.healthconnect

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import org.avmedia.smartsync.plugin.api.AndroidPermission
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
    override val description = "Writes the health data of watches with a health log to Android Health Connect, so " +
        "apps such as Google Fit can show it. Each record is written once and replaced when its value changes. It only " +
        "writes and never reads Health Connect."
    override val setupNeeds = "A watch with a health log and Health Connect's write permissions"
    override val infoUrl = "$REPO/blob/main/docs/healthconnect.md"
    override val sourceUrl = "$REPO/tree/main/healthconnect"
    override val issuesUrl = "$REPO/issues"
    override val author = "ExcuseMi"
    override val license = "Apache-2.0"
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

    /** Health Connect's own request screen for every write permission: the host opens it when the plugin is turned on. */
    override fun permissionIntent(context: Context): Intent? = runCatching {
        PermissionController.createRequestPermissionResultContract().createIntent(context, ALL_PERMISSIONS)
    }.getOrNull()

    /**
     * The write permissions not granted. Without Health Connect (not installed, needs an update) none: the plugin's screen
     * says what to do then, a request could not open.
     */
    override suspend fun missingPermissions(context: Context): Set<String>? {
        if (HealthConnectSink(context).status() != SinkStatus.AVAILABLE) return emptySet()
        val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return ALL_PERMISSIONS - granted
    }

    companion object {
        const val ID = "health_connect"
        const val REPO = "https://github.com/ExcuseMi/smartsync-plugins"

        /** The plugin's version (healthconnect/build.gradle, tag healthconnect-v<version>). */
        const val VERSION = "0.1.1"

        val ACCESS = PluginAccess(
            reads = setOf(DataUse.STEPS, DataUse.HEART_RATE, DataUse.SLEEP, DataUse.BLOOD_OXYGEN, DataUse.WORKOUTS),
            sendsTo = listOf("Health Connect on this phone"),
            stores = listOf("which records it wrote, so none is written twice"),
            requires = setOf(Capability.HEALTH_LOG),
            uses = setOf(Capability.STEPS, Capability.HEART_RATE, Capability.SLEEP, Capability.BLOOD_OXYGEN, Capability.WORKOUTS),
            androidPermissions = RecordType.entries.map { AndroidPermission(HealthConnectSink.permission(it), PERMISSION_REASON) },
        )

        /** One reason for all of them: the status line reads "Needs permission: writing to Health Connect". */
        const val PERMISSION_REASON = "writing to Health Connect"

        val ALL_PERMISSIONS: Set<String> get() = HealthConnectSink.permissions(RecordType.entries.toSet())
    }
}
