package org.avmedia.smartsync.plugin.healthconnect

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class HealthWriterTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-11-24")
    private val read = HistoryRange(LocalDateTime.parse("2026-11-24T08:00"), LocalDateTime.parse("2026-11-24T11:00"))
    private val allKinds = DataKind.ALL.toSet()

    private fun host() = FakeHost().apply {
        records[DataKind.Hours] = listOf(HourRecord(day.atTime(8, 0), steps = 500), HourRecord(day.atTime(9, 0), steps = 900))
        records[DataKind.HeartRate] = listOf(HeartRateSample(day.atTime(8, 0), 60), HeartRateSample(day.atTime(8, 2), 62))
    }

    @Test fun aSecondReadOfTheSameHoursWritesNothing() = runTest {
        val host = host(); val sink = FakeSink()
        val writer = HealthWriter(host, sink, clock)
        writer.healthRead(H5600, read, allKinds)
        assertEquals(3, sink.written.size)
        assertTrue(sink.written.all { it.version == 0L })
        writer.healthRead(H5600, read, allKinds)
        assertEquals(1, sink.batches.size)
        assertEquals(listOf("Health Connect: wrote steps 2, heart rate 1"), host.logs)
    }

    @Test fun aChangedHourIsAnUpdateOfTheSameRecordWithTheNextVersion() = runTest {
        val host = host(); val sink = FakeSink()
        val writer = HealthWriter(host, sink, clock)
        writer.healthRead(H5600, read, allKinds)
        host.records[DataKind.Hours] = listOf(HourRecord(day.atTime(8, 0), steps = 500), HourRecord(day.atTime(9, 0), steps = 1400))
        writer.healthRead(H5600, read, allKinds)
        val second = sink.batches[1]
        assertEquals(listOf("steps/2026-11-24T09:00"), second.map { it.record.key })
        assertEquals(1L, second.single().version)
    }

    @Test fun aNewWriterOnTheSameStorageStillKnowsWhatWasWritten() = runTest {
        val host = host(); val sink = FakeSink()
        HealthWriter(host, sink, clock).healthRead(H5600, read, allKinds)
        HealthWriter(host, sink, clock).healthRead(H5600, read, allKinds)
        assertEquals(1, sink.batches.size)
    }

    @Test fun nothingIsWrittenWithoutPermissionAndItIsLoggedOnce() = runTest {
        val host = host(); val sink = FakeSink(allowed = emptySet())
        val writer = HealthWriter(host, sink, clock)
        writer.healthRead(H5600, read, allKinds)
        writer.healthRead(H5600, read, allKinds)
        assertTrue(sink.written.isEmpty())
        assertEquals(1, host.logs.size)
        assertTrue(host.storage(H5600).keys().isEmpty())
    }

    @Test fun onlyTheAllowedTypesAreWritten() = runTest {
        val host = host(); val sink = FakeSink(allowed = setOf(RecordType.STEPS))
        HealthWriter(host, sink, clock).healthRead(H5600, read, allKinds)
        assertEquals(setOf(RecordType.STEPS), sink.written.map { it.record.type }.toSet())
    }

    @Test fun nothingIsWrittenWhenHealthConnectIsMissing() = runTest {
        val host = host(); val sink = FakeSink(status = SinkStatus.NOT_INSTALLED)
        HealthWriter(host, sink, clock).healthRead(H5600, read, allKinds)
        assertTrue(sink.written.isEmpty())
    }

    @Test fun aWatchWithoutHealthDataGetsNothing() = runTest {
        val host = host().apply { paired = listOf(PairedWatch(H5600, "CASIO GW-B5600", setOf(Capability.ALARMS), true)) }
        val sink = FakeSink()
        HealthWriter(host, sink, clock).healthRead(H5600, read, allKinds)
        assertTrue(sink.written.isEmpty())
        assertTrue(host.logs.isEmpty())
    }

    @Test fun aFailedWriteIsLoggedOnceAndRetriedNextTime() = runTest {
        val host = host(); val sink = FakeSink().apply { fail = IllegalStateException("refused") }
        val writer = HealthWriter(host, sink, clock)
        writer.healthRead(H5600, read, allKinds)
        writer.healthRead(H5600, read, allKinds)
        assertEquals(1, host.failures.size)
        sink.fail = null
        writer.healthRead(H5600, read, allKinds)
        assertEquals(3, sink.written.size)
    }

    @Test fun theSameWorkoutStoredTwiceIsWrittenOnce() = runTest {
        val host = host(); val sink = FakeSink()
        val plugin = HealthConnectPlugin { sink }
        plugin.activate(host)
        val w = WorkoutRecord("w9", Instant.parse("2026-11-24T07:00:00Z"), Instant.parse("2026-11-24T07:30:00Z"), 1, "Run", kcal = 300)
        plugin.onEvent(WatchEvent.WorkoutStored(H5600, w))
        plugin.onEvent(WatchEvent.WorkoutStored(H5600, w))
        assertEquals(listOf("exercise/w9", "kcal/w9"), sink.written.map { it.record.key })
        assertTrue(host.needs!!.healthReads)
    }

    @Test fun nothingIsWrittenWhenThePluginIsOff() = runTest {
        val host = host(); val sink = FakeSink()
        val plugin = HealthConnectPlugin { sink }
        plugin.onEvent(WatchEvent.HealthRead(H5600, read, allKinds))
        plugin.activate(host)
        plugin.deactivate()
        plugin.onEvent(WatchEvent.HealthRead(H5600, read, allKinds))
        assertTrue(sink.written.isEmpty())
    }
}
