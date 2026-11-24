package org.avmedia.smartsync.plugin.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.avmedia.smartsync.plugin.api.WatchId
import kotlin.reflect.KClass

/** Writes to the phone's Health Connect. */
class HealthConnectSink(private val context: Context) : HealthSink {

    override fun status(): SinkStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> SinkStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SinkStatus.UPDATE_REQUIRED
        else -> SinkStatus.NOT_INSTALLED
    }

    override suspend fun granted(): Set<RecordType> {
        if (status() != SinkStatus.AVAILABLE) return emptySet()
        val have = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return RecordType.entries.filter { permission(it) in have }.toSet()
    }

    override suspend fun write(watch: WatchId, watchModel: String?, records: List<Pending>) {
        if (records.isEmpty()) return
        val client = HealthConnectClient.getOrCreate(context)
        val hc = records.map { HcRecords.toRecord(it, watch.key, watchModel) }
        hc.chunked(CHUNK).forEach { client.insertRecords(it) }
    }

    companion object {
        private const val CHUNK = 500

        fun recordClass(type: RecordType): KClass<out Record> = when (type) {
            RecordType.STEPS -> StepsRecord::class
            RecordType.HEART_RATE -> HeartRateRecord::class
            RecordType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            RecordType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
            RecordType.SLEEP -> SleepSessionRecord::class
            RecordType.EXERCISE -> ExerciseSessionRecord::class
            RecordType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord::class
        }

        /** The write permission of [type]. */
        fun permission(type: RecordType): String = HealthPermission.getWritePermission(recordClass(type))

        fun permissions(types: Set<RecordType>): Set<String> = types.map { permission(it) }.toSet()
    }
}
