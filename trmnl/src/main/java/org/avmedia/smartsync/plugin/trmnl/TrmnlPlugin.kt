package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.smartsync.plugin.api.DataUse
import org.avmedia.smartsync.plugin.api.PluginAccess
import org.avmedia.smartsync.plugin.api.PluginApi
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginUi
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchPlugin
import timber.log.Timber

/**
 * Pushes each watch's values to a TRMNL private plugin (webhook strategy), so a TRMNL shows the watch without Home
 * Assistant. Events only mark a watch as changed; sending runs in the plugin's own scope and never holds the watch.
 */
class TrmnlPlugin(
    private val transport: WebhookTransport = HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchPlugin {
    override val id = ID
    override val name = "TRMNL"
    override val summary = "Shows the watch's steps, heart rate and battery on a TRMNL."
    override val description = "Pushes each watch's steps, heart rate, battery and more to a TRMNL private plugin, so " +
        "a TRMNL e-paper display shows the watch without Home Assistant. It sends only what changed and stays under " +
        "TRMNL's webhook limits."
    override val setupNeeds = "A TRMNL private plugin with the webhook strategy, and its webhook URL"
    override val infoUrl = "$REPO/blob/main/docs/trmnl.md"
    override val sourceUrl = "$REPO/tree/main/trmnl"
    override val issuesUrl = "$REPO/issues"
    override val author = "ExcuseMi"
    override val license = "Apache-2.0"
    override val apiVersion = PluginApi.VERSION
    override val version = VERSION
    override val access = ACCESS

    private var host: PluginHost? = null
    private var sender: Sender? = null
    private var scope: CoroutineScope? = null
    private val flushLock = Mutex()
    private val lock = Any()
    private var job: Job? = null
    private var wakeAt = Long.MAX_VALUE

    private val _status = MutableStateFlow(SendStatus())
    val status: StateFlow<SendStatus> get() = _status

    override suspend fun activate(host: PluginHost) {
        this.host = host
        host.declareNeeds(NEEDS)
        sender = Sender(host, transport, clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settingsChanged()
    }

    override suspend fun deactivate() {
        scope?.cancel()
        scope = null
        synchronized(lock) { job = null; wakeAt = Long.MAX_VALUE }
        sender = null
        host = null
    }

    override suspend fun onEvent(event: WatchEvent) {
        val s = sender ?: return
        when (event) {
            is WatchEvent.ValuesUpdated, is WatchEvent.HealthRead, is WatchEvent.Connected, is WatchEvent.Disconnected,
            is WatchEvent.SettingChanged -> {
                event.watch?.let { s.markDirty(it) }
                schedule(clock() + Limits.COALESCE_MILLIS)
            }
            // The host's periodic wake-up, also with the app stopped: what changed (or waits after a failure) goes out
            // now, before the host lets the process go; a send is still kept to the interval and the back-off.
            else -> Unit
        }
    }

    /** After a settings change (or at start): every watch is checked again; only changed payloads go out. */
    fun settingsChanged() {
        sender?.markAllDirty()
        schedule(clock() + Limits.COALESCE_MILLIS)
    }

    internal fun host(): PluginHost? = host

    private fun schedule(at: Long) {
        val sc = scope ?: return
        synchronized(lock) {
            if (job?.isActive == true && wakeAt <= at) return
            job?.cancel()
            wakeAt = at
            job = sc.launch {
                delay((at - clock()).coerceAtLeast(0))
                runFlush()
            }
        }
    }

    private suspend fun runFlush() {
        val s = sender ?: return
        val next = flushLock.withLock {
            try {
                s.flush()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(ID).w("TRMNL: send failed: %s", e.javaClass.simpleName)
                clock() + Limits.COALESCE_MILLIS * 5
            }
        }
        _status.value = s.status
        synchronized(lock) { job = null; wakeAt = Long.MAX_VALUE }
        val again = next ?: if (s.pending()) clock() + Limits.COALESCE_MILLIS else null
        if (again != null) schedule(again)
    }

    override val ui: PluginUi = TrmnlUi(this)

    companion object {
        const val ID = "trmnl"
        const val REPO = "https://github.com/ExcuseMi/smartsync-plugins"

        /** About as often as the shortest send interval allows, and the host's least (15 minutes). */
        const val WAKE_MINUTES = 15

        val NEEDS = PluginNeeds(values = Payload.VALUE_KEYS, healthReads = true, wakeEveryMinutes = WAKE_MINUTES)

        /** The plugin's version (trmnl/build.gradle, tag trmnl-v<version>). */
        const val VERSION = "0.1.0"

        val ACCESS = PluginAccess(
            reads = setOf(DataUse.STEPS, DataUse.HEART_RATE, DataUse.BATTERY_AND_TEMPERATURE, DataUse.WATCH_INFO, DataUse.WATCH_SETTINGS, DataUse.PHONE),
            sendsTo = listOf("your TRMNL private plugin (webhook)"),
            stores = listOf("TRMNL webhook URLs and what was sent last"),
        )
    }
}
