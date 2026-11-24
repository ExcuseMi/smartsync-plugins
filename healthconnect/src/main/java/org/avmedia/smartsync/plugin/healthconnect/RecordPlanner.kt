package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.DayRecord
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.NightRecord
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Maps the host's stored records to [Planned] records. A null field is a gap and is never written as 0. Values with a
 * unit that is not proven ([WorkoutRecord.unconfirmed]) are left out.
 */
class RecordPlanner(private val zone: ZoneId) {

    fun instant(t: LocalDateTime): Instant = t.atZone(zone).toInstant()

    fun steps(hours: List<HourRecord>): List<Planned> = hours.mapNotNull { h ->
        val count = h.steps?.toLong() ?: return@mapNotNull null
        if (count !in 1..MAX_STEPS_PER_HOUR) return@mapNotNull null
        PlannedSteps("steps/${h.start}", instant(h.start), instant(h.start.plusHours(1)), count)
    }

    /** One record per day with that day's samples. */
    fun heartRate(samples: List<HeartRateSample>): List<Planned> =
        samples.filter { it.bpm in 1..300 }
            .groupBy { it.time.toLocalDate() }
            .toSortedMap()
            .map { (day, list) ->
                val points = list.sortedBy { it.time }.distinctBy { it.time }.map { BpmSample(instant(it.time), it.bpm.toLong()) }
                PlannedHeartRate("hr/$day", points)
            }

    /** Resting heart rate and blood oxygen of finished days ([today] and later are still changing). */
    fun days(days: List<DayRecord>, today: LocalDate): List<Planned> = days.filter { it.date.isBefore(today) }.flatMap { d ->
        val at = instant(d.date.atTime(23, 59, 59))
        listOfNotNull(
            d.hrRest?.takeIf { it in 1..300 }?.let { PlannedRestingHeartRate("rhr/${d.date}", at, it.toLong()) },
            d.spo2?.takeIf { it in 1..100 }?.let { PlannedOxygen("spo2/${d.date}", at, it.toDouble()) },
        )
    }

    /** The session when both ends are known; its stages only when they are proven and every one is understood. */
    fun night(n: NightRecord): Planned? {
        val asleep = n.fellAsleep ?: return null
        val woke = n.wokeUp ?: return null
        if (!asleep.isBefore(woke)) return null
        return PlannedSleep("sleep/${n.date}", instant(asleep), instant(woke), if (n.stagesConfirmed) stages(n, asleep, woke) else emptyList())
    }

    private fun stages(n: NightRecord, asleep: LocalDateTime, woke: LocalDateTime): List<PlannedStage> {
        val points = n.stages.sortedBy { it.start }.filter { !it.start.isBefore(asleep) && it.start.isBefore(woke) }
        val kinds = points.map { sleepKind(it.stage) ?: return emptyList() }
        return points.indices.mapNotNull { i ->
            val end = points.getOrNull(i + 1)?.start ?: woke
            if (!points[i].start.isBefore(end)) null else PlannedStage(instant(points[i].start), instant(end), kinds[i])
        }
    }

    /** The session (sport, start, end) and its energy when the unit is proven. Distance waits for a stated unit. */
    fun workout(w: WorkoutRecord): List<Planned> {
        val end = w.end ?: w.totalSec?.let { w.start.plusSeconds(it.toLong()) } ?: return emptyList()
        if (!w.start.isBefore(end)) return emptyList()
        val session = PlannedExercise("exercise/${w.id}", w.start, end, SportTypes.exerciseType(w.sportName), w.sportName)
        val kcal = w.kcal?.takeIf { it > 0 && "kcal" !in w.unconfirmed }
            ?.let { PlannedCalories("kcal/${w.id}", w.start, end, it.toDouble()) }
        return listOfNotNull(session, kcal)
    }

    companion object {
        const val MAX_STEPS_PER_HOUR = 100_000L

        fun sleepKind(stage: String): SleepKind? = when (stage.trim().lowercase()) {
            "awake", "wake" -> SleepKind.AWAKE
            "light" -> SleepKind.LIGHT
            "deep" -> SleepKind.DEEP
            "rem" -> SleepKind.REM
            else -> null
        }
    }
}
