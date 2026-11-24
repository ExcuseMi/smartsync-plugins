package org.avmedia.smartsync.plugin.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** [Pending] records as Health Connect records: recorded automatically by a watch, with a stable clientRecordId. */
object HcRecords {
    const val ID_PREFIX = "smartsync"

    fun clientRecordId(watchKey: String, key: String): String = "$ID_PREFIX/$watchKey/$key"

    fun toRecord(p: Pending, watchKey: String, watchModel: String?, zone: ZoneId = ZoneId.systemDefault()): Record {
        val meta = Metadata.autoRecorded(
            device = Device(type = Device.TYPE_WATCH, manufacturer = "Casio", model = watchModel),
            clientRecordId = clientRecordId(watchKey, p.record.key),
            clientRecordVersion = p.version,
        )
        fun off(t: Instant): ZoneOffset = zone.rules.getOffset(t)
        return when (val r = p.record) {
            is PlannedSteps -> StepsRecord(r.start, off(r.start), r.end, off(r.end), r.count, meta)
            is PlannedHeartRate -> HeartRateRecord(
                r.start, off(r.start), r.end, off(r.end),
                r.samples.map { HeartRateRecord.Sample(it.time, it.bpm) }, meta,
            )
            is PlannedRestingHeartRate -> RestingHeartRateRecord(r.time, off(r.time), r.bpm, meta)
            is PlannedOxygen -> OxygenSaturationRecord(r.time, off(r.time), Percentage(r.percent), meta)
            is PlannedSleep -> SleepSessionRecord(
                startTime = r.start, startZoneOffset = off(r.start), endTime = r.end, endZoneOffset = off(r.end),
                metadata = meta,
                stages = r.stages.map { SleepSessionRecord.Stage(it.start, it.end, stageType(it.stage)) },
            )
            is PlannedExercise -> ExerciseSessionRecord(
                startTime = r.start, startZoneOffset = off(r.start), endTime = r.end, endZoneOffset = off(r.end),
                metadata = meta, exerciseType = r.exerciseType, title = r.title,
            )
            is PlannedCalories -> TotalCaloriesBurnedRecord(r.start, off(r.start), r.end, off(r.end), Energy.kilocalories(r.kcal), meta)
        }
    }

    fun stageType(kind: SleepKind): Int = when (kind) {
        SleepKind.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SleepKind.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        SleepKind.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        SleepKind.REM -> SleepSessionRecord.STAGE_TYPE_REM
    }
}
