package org.avmedia.smartsync.plugin.tasker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.smartsync.plugin.api.PluginHost
import timber.log.Timber

/** Between the plugin and the Android parts the hosts call (receivers, edit screens), which have no plugin object. */
internal object AndroidSignals : Signals {
    private val active = MutableStateFlow<PluginHost?>(null)

    /** For the receivers' work that outlives onReceive. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The host while the plugin is on; null while it is off. */
    val host: PluginHost? get() = active.value

    /** The host, waiting up to [millis] for the app to start the plugin (a receiver may start the app's process). */
    suspend fun awaitHost(millis: Long = 8_000): PluginHost? = withTimeoutOrNull(millis) { active.filterNotNull().first() }

    override fun started(host: PluginHost) { active.value = host }

    override fun stopped() { active.value = null }

    override fun event(fired: Fired) {
        val context = host?.context ?: return
        requestQuery(context, EditEventActivity::class.java.name, BundleCodec.toBundle(fired.data))
    }

    override fun stateChanged() {
        val context = host?.context ?: return
        requestQuery(context, EditStateActivity::class.java.name, null)
    }

    /** Asks every Locale host to query the configurations of [activity] (with event data for an event). */
    private fun requestQuery(context: Context, activity: String, passThrough: Bundle?) {
        val intent = Intent(Protocol.ACTION_REQUEST_QUERY).putExtra(Protocol.EXTRA_ACTIVITY, activity)
        if (passThrough != null) intent.putExtra(Protocol.EXTRA_PASS_THROUGH, passThrough)
        try {
            context.sendBroadcast(intent)
        } catch (e: RuntimeException) {
            Timber.tag("tasker").w("request query not sent: %s", e.javaClass.simpleName)
        }
    }
}

/** Config bundles hold strings only. */
internal object BundleCodec {
    fun toMap(b: Bundle?): Map<String, String> {
        if (b == null) return emptyMap()
        return b.keySet().mapNotNull { k -> b.getString(k)?.let { k to it } }.toMap()
    }

    fun toBundle(m: Map<String, String>): Bundle = Bundle().apply { m.forEach { (k, v) -> putString(k, v) } }
}
