package org.avmedia.smartsync.plugin.tasker

import android.content.Context
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.FailReason
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.HistoryRecord
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.PluginStatus
import org.avmedia.smartsync.plugin.api.PluginStorage
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingsSnapshot
import org.avmedia.smartsync.plugin.api.ValueInfo
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchNotification
import org.avmedia.smartsync.plugin.api.WatchValues

class MemStorage : PluginStorage {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
    override fun putAll(values: Map<String, String?>) { values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
}

/** A host that records the plugin's writes and answers with [answer]; no watch, no Android. */
class FakeHost : PluginHost {
    override val context: Context get() = error("no Android context in JVM tests")
    override val appVersion = "test"
    override val apiVersion = "0.1.0"

    val paired = mutableListOf<PairedWatch>()
    val values = HashMap<WatchId, Map<String, Any?>>()
    val catalogs = HashMap<WatchId, List<SettingInfo>>()
    val notified = mutableListOf<Triple<WatchId, WatchNotification, Delivery>>()
    val commands = mutableListOf<Triple<WatchId, WatchCommand, Delivery>>()
    var answer: PluginResult<Delivered> = PluginResult.Ok(Delivered.NOW)
    var needs: PluginNeeds? = null
    val statuses = mutableListOf<PluginStatus>()
    private val store = MemStorage()

    fun addWatch(key: String, name: String, connected: Boolean = true): WatchId {
        val id = WatchId(key)
        paired += PairedWatch(id, name, setOf(Capability.NOTIFICATIONS, Capability.STEPS, Capability.HEART_RATE), connected)
        return id
    }

    override fun watches(): List<PairedWatch> = paired.toList()
    override fun connected(): WatchId? = paired.firstOrNull { it.connected }?.id
    override fun current(): WatchId? = connected() ?: paired.firstOrNull()?.id
    override fun watchName(watch: WatchId): String? = paired.firstOrNull { it.id == watch }?.name
    override suspend fun notify(watch: WatchId, notification: WatchNotification, delivery: Delivery): PluginResult<Delivered> {
        notified += Triple(watch, notification, delivery); return answer
    }
    override suspend fun command(watch: WatchId, command: WatchCommand, delivery: Delivery): PluginResult<Delivered> {
        commands += Triple(watch, command, delivery); return answer
    }
    override fun valueCatalog() = emptyList<ValueInfo>()
    override fun lastValues(watch: WatchId) = WatchValues(watch, values[watch].orEmpty())
    override fun settings(watch: WatchId) = SettingsSnapshot(watch, emptyMap(), null)
    override fun settingCatalog(watch: WatchId): List<SettingInfo> = catalogs[watch].orEmpty()
    override suspend fun <T : HistoryRecord> history(watch: WatchId, kind: DataKind<T>, range: HistoryRange): PluginResult<List<T>> =
        PluginResult.Failed(FailReason.NOT_SUPPORTED, "test")
    override fun storage(): PluginStorage = store
    override fun storage(watch: WatchId): PluginStorage = store
    override fun missingPermissions(): Set<String> = emptySet()
    override fun requestPermissions() {}
    override fun declareNeeds(needs: PluginNeeds) { this.needs = needs }
    override fun status(status: PluginStatus) { statuses += status }
}
