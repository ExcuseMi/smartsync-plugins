package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * History for systems without Home Assistant statistics: retained topics with the real times.
 * `<base>/<watch>/history/steps_hourly/<date>` = `[{"t":"2026-11-23T10:00+01:00","v":812},...]`,
 * `<base>/<watch>/history/heart_rate_hourly/<date>` = `[{"t":...,"mean":72.5,"min":60,"max":101},...]`,
 * `<base>/<watch>/history/workout/<id>` = one workout. The live sensors carry only the values of now.
 */
object HistoryTopics {
    const val DAYS = 7L

    fun build(topics: Topics, w: WatchId, hours: List<HourRecord>, samples: List<HeartRateSample>, workouts: List<WorkoutRecord>, zone: ZoneId): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        hours.filter { it.steps != null }.groupBy { it.start.toLocalDate() }.toSortedMap().forEach { (day, l) ->
            out[topics.history(w, "steps_hourly", day)] = Json.write(
                l.sortedBy { it.start }.map { linkedMapOf("t" to time(it.start, zone), "v" to it.steps) },
            )
        }
        samples.groupBy { it.time.toLocalDate() }.toSortedMap().forEach { (day, l) ->
            val byHour = l.groupBy { it.time.truncatedTo(ChronoUnit.HOURS) }.toSortedMap()
            out[topics.history(w, "heart_rate_hourly", day)] = Json.write(
                byHour.map { (h, s) ->
                    val v = s.map { it.bpm }
                    linkedMapOf("t" to time(h, zone), "mean" to Math.round(v.average() * 10) / 10.0, "min" to v.min(), "max" to v.max())
                },
            )
        }
        workouts.forEach { r ->
            out[topics.history(w, "workout", Topics.safe(r.id))] = Json.write(
                linkedMapOf(
                    "id" to r.id, "sport" to r.sportName, "sport_id" to r.sport,
                    "start" to r.start.atZone(zone).toOffsetDateTime().toString(),
                    "end" to r.end?.atZone(zone)?.toOffsetDateTime()?.toString(),
                    "duration_s" to r.totalSec, "distance_m" to r.distanceM, "kcal" to r.kcal,
                    "hr_avg" to r.hrAvg, "hr_min" to r.hrMin, "hr_max" to r.hrMax, "ascent" to r.ascent, "descent" to r.descent,
                    "unconfirmed" to r.unconfirmed.sorted(),
                ).filterValues { it != null },
            )
        }
        return out
    }

    /** The first day kept: older day topics are emptied. */
    fun firstDay(today: LocalDate): LocalDate = today.minusDays(DAYS - 1)

    private fun time(t: java.time.LocalDateTime, zone: ZoneId): String = t.atZone(zone).toOffsetDateTime().toString()
}
