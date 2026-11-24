package org.avmedia.smartsync.plugin.healthconnect

import android.content.Context
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.CommandResult
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.HistoryRecord
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginNeeds
import org.avmedia.smartsync.plugin.api.PluginStorage
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingsSnapshot
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchNotification
import org.avmedia.smartsync.plugin.api.WatchValues

val H5600 = WatchId("gshock-aabbccddeeff")
val HEALTH_CAPS = setOf(Capability.HEALTH_LOG, Capability.STEPS, Capability.HEART_RATE, Capability.SLEEP, Capability.WORKOUTS, Capability.BLOOD_OXYGEN)

class MemoryStorage : PluginStorage {
    val map = sortedMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun delete(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A host with stored records per kind; only what the plugin uses does anything. */
class FakeHost(var paired: List<PairedWatch> = listOf(PairedWatch(H5600, "CASIO GBX-H5600", HEALTH_CAPS, true))) : PluginHost {
    val records = mutableMapOf<DataKind<*>, List<HistoryRecord>>()
    val logs = mutableListOf<String>()
    val failures = mutableListOf<String>()
    var needs: PluginNeeds? = null
    private val shared = MemoryStorage()
    private val perWatch = mutableMapOf<WatchId, MemoryStorage>()

    override val context: Context get() = throw UnsupportedOperationException("no context in tests")
    override val appVersion = "test"
    override val apiVersion = org.avmedia.smartsync.plugin.api.PluginApi.VERSION
    override fun watches() = paired
    override fun connected(): WatchId? = paired.firstOrNull { it.connected }?.id
    override fun current(): WatchId? = connected()
    override fun watchName(watch: WatchId) = paired.firstOrNull { it.id == watch }?.name
    override suspend fun notify(watch: WatchId, notification: WatchNotification, delivery: Delivery) = CommandResult.NotSupported("test")
    override suspend fun command(watch: WatchId, command: WatchCommand, delivery: Delivery) = CommandResult.NotSupported("test")
    override fun lastValues(watch: WatchId) = WatchValues(watch)
    override fun settings(watch: WatchId) = SettingsSnapshot(watch, emptyMap(), null)
    override fun settingCatalog(watch: WatchId) = emptyList<SettingInfo>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : HistoryRecord> history(watch: WatchId, kind: DataKind<T>, range: HistoryRange): List<T> =
        (records[kind].orEmpty() as List<T>).filter { range.contains(timeOf(it)) }

    override fun storage(): PluginStorage = shared
    override fun storage(watch: WatchId): PluginStorage = perWatch.getOrPut(watch) { MemoryStorage() }
    override fun moveWatchData(from: WatchId, to: WatchId) = Unit
    override fun declareNeeds(needs: PluginNeeds) { this.needs = needs }
    override fun log(line: String) { logs += line }
    override fun failure(line: String, error: Throwable) { failures += "$line: ${error.message}" }

    private fun timeOf(r: HistoryRecord): java.time.LocalDateTime = when (r) {
        is org.avmedia.smartsync.plugin.api.HourRecord -> r.start
        is org.avmedia.smartsync.plugin.api.HeartRateSample -> r.time
        is org.avmedia.smartsync.plugin.api.DayRecord -> r.date.atTime(12, 0)
        is org.avmedia.smartsync.plugin.api.NightRecord -> r.date.atTime(12, 0)
        is org.avmedia.smartsync.plugin.api.WorkoutRecord -> java.time.LocalDateTime.ofInstant(r.start, java.time.ZoneOffset.UTC)
    }
}

class FakeSink(
    var status: SinkStatus = SinkStatus.AVAILABLE,
    var allowed: Set<RecordType> = RecordType.entries.toSet(),
) : HealthSink {
    val batches = mutableListOf<List<Pending>>()
    var fail: Exception? = null
    val written: List<Pending> get() = batches.flatten()

    override fun status() = status
    override suspend fun granted() = if (status == SinkStatus.AVAILABLE) allowed else emptySet()
    override suspend fun write(watch: WatchId, watchModel: String?, records: List<Pending>) {
        fail?.let { throw it }
        batches += records
    }
}
