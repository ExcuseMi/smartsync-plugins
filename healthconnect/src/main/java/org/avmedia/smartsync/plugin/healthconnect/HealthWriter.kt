package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * After each health read or stored workout: maps what the host stored, keeps what the watch can record and the person
 * allowed, leaves out what was written unchanged before, and writes the rest. One App log line per write, an error once.
 */
class HealthWriter(
    private val host: PluginHost,
    private val sink: HealthSink,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val planner = RecordPlanner(clock.zone)
    private var lastProblem: String? = null

    suspend fun healthRead(watch: WatchId, range: HistoryRange, kinds: Set<DataKind<*>>) {
        val types = allowed(watch) ?: return
        // Whole days: a day's heart rate is one record, so it is written with all of that day's samples.
        val days = HistoryRange.days(range.from.toLocalDate(), range.to.toLocalDate())
        val planned = buildList {
            if (DataKind.Hours in kinds && RecordType.STEPS in types) addAll(planner.steps(host.history(watch, DataKind.Hours, days)))
            if (DataKind.HeartRate in kinds && RecordType.HEART_RATE in types) {
                addAll(planner.heartRate(host.history(watch, DataKind.HeartRate, days)))
            }
            if (DataKind.Days in kinds && (types.contains(RecordType.RESTING_HEART_RATE) || types.contains(RecordType.OXYGEN_SATURATION))) {
                addAll(planner.days(host.history(watch, DataKind.Days, days), LocalDate.now(clock)))
            }
            if (DataKind.Nights in kinds && RecordType.SLEEP in types) {
                addAll(host.history(watch, DataKind.Nights, days).mapNotNull { planner.night(it) })
            }
            if (DataKind.Workouts in kinds && RecordType.EXERCISE in types) {
                host.history(watch, DataKind.Workouts, days).forEach { addAll(planner.workout(it)) }
            }
        }
        write(watch, planned.filter { it.type in types })
    }

    suspend fun workoutStored(watch: WatchId, workout: WorkoutRecord) {
        val types = allowed(watch) ?: return
        write(watch, planner.workout(workout).filter { it.type in types })
    }

    /** The types to write for [watch]; null when nothing can be written now (logged once). */
    private suspend fun allowed(watch: WatchId): Set<RecordType>? {
        val offered = RecordType.offeredBy(host.watches().firstOrNull { it.id == watch }?.capabilities.orEmpty())
        if (offered.isEmpty()) return null
        val status = sink.status()
        if (status != SinkStatus.AVAILABLE) return problem(if (status == SinkStatus.UPDATE_REQUIRED) "needs an update" else "is not installed")
        val types = offered intersect sink.granted()
        if (types.isEmpty()) return problem("has no write permission for this app")
        return types
    }

    private suspend fun write(watch: WatchId, planned: List<Planned>) {
        val ledger = WriteLedger(host.storage(watch))
        val pending = ledger.pending(planned)
        if (pending.isEmpty()) return
        try {
            sink.write(watch, host.watches().firstOrNull { it.id == watch }?.name, pending)
        } catch (e: Exception) {
            if (lastProblem != "write") host.failure("Health Connect: write failed", e)
            lastProblem = "write"
            return
        }
        ledger.commit(pending)
        lastProblem = null
        val counts = pending.groupingBy { it.record.type }.eachCount().toSortedMap()
        val summary = counts.entries.joinToString(", ") { (t, n) -> "${label(t)} $n" }
        host.log("Health Connect: wrote $summary")
        host.storage().put(LAST_WRITE, LocalDateTime.now(clock).withNano(0).toString())
        host.storage().put(LAST_SUMMARY, summary)
    }

    private fun problem(text: String): Set<RecordType>? {
        if (lastProblem != text) host.log("Health Connect $text: nothing written")
        lastProblem = text
        return null
    }

    companion object {
        const val LAST_WRITE = "last_write"
        const val LAST_SUMMARY = "last_summary"

        fun label(t: RecordType): String = when (t) {
            RecordType.STEPS -> "steps"
            RecordType.HEART_RATE -> "heart rate"
            RecordType.RESTING_HEART_RATE -> "resting heart rate"
            RecordType.OXYGEN_SATURATION -> "blood oxygen"
            RecordType.SLEEP -> "sleep"
            RecordType.EXERCISE -> "workouts"
            RecordType.TOTAL_CALORIES -> "workout energy"
        }
    }
}
