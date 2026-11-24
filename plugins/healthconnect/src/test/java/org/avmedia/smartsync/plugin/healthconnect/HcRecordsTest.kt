package org.avmedia.smartsync.plugin.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class HcRecordsTest {
    private val steps = PlannedSteps("steps/2026-11-24T08:00", Instant.parse("2026-11-24T08:00:00Z"), Instant.parse("2026-11-24T09:00:00Z"), 812)

    @Test fun theClientRecordIdIsStablePerWatchAndRecordAndCarriesTheVersion() {
        val r = HcRecords.toRecord(Pending(steps, 3), H5600.key, "CASIO GBX-H5600", ZoneOffset.UTC) as StepsRecord
        assertEquals("smartsync/gshock-aabbccddeeff/steps/2026-11-24T08:00", r.metadata.clientRecordId)
        assertEquals(3L, r.metadata.clientRecordVersion)
        assertEquals(812L, r.count)
        val other = HcRecords.toRecord(Pending(steps, 0), "gshock-112233445566", null, ZoneOffset.UTC)
        assertNotEquals(r.metadata.clientRecordId, other.metadata.clientRecordId)
    }

    @Test fun sleepStagesMapToHealthConnectStages() {
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, HcRecords.stageType(SleepKind.DEEP))
        assertEquals(SleepSessionRecord.STAGE_TYPE_REM, HcRecords.stageType(SleepKind.REM))
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, HcRecords.stageType(SleepKind.AWAKE))
    }

    @Test fun theFingerprintChangesWithAValueAndNotOtherwise() {
        assertEquals(steps.fingerprint(), steps.copy().fingerprint())
        assertNotEquals(steps.fingerprint(), steps.copy(count = 813).fingerprint())
    }
}
