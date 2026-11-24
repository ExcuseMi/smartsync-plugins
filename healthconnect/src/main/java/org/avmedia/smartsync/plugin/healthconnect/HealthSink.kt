package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.WatchId

/** Where records go. [HealthConnectSink] in the app; a fake in tests. */
interface HealthSink {
    fun status(): SinkStatus

    /** The record types the person allowed this app to write. */
    suspend fun granted(): Set<RecordType>

    /** Writes [records] (inserts, or updates by clientRecordId); throws when Health Connect refuses. */
    suspend fun write(watch: WatchId, watchModel: String?, records: List<Pending>)
}

enum class SinkStatus { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }
