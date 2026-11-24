package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** One hour of a statistic: [start] is a UTC hour start, [values] the fields Home Assistant takes (sum, mean, ...). */
data class StatRow(val start: Instant, val values: Map<String, Number>)

/**
 * Which hours to fill. Home Assistant builds the history itself from the live sensors (their state_class); only the
 * hours it has no row for are filled, and only between two hours it has, so its own sums stay as they are.
 */
object StatsPlan {
    /**
     * Steps: [present] is Home Assistant's sum per hour, [steps] the app's steps per hour. Each run of missing hours
     * between two present ones gets sums that grow by the app's steps, never above the next present sum (Home
     * Assistant counted the late steps in the hour it saw them). Missing hours at the end are left: Home Assistant
     * continues from its own last sum and counts them when the sensor is back.
     */
    fun steps(hours: List<Instant>, steps: Map<Instant, Int>, present: Map<Instant, Double>): List<StatRow> {
        val rows = ArrayList<StatRow>()
        var i = 0
        while (i < hours.size) {
            if (hours[i] in present) { i++; continue }
            val runStart = i
            while (i < hours.size && hours[i] !in present) i++
            val prev = present.filterKeys { it.isBefore(hours[runStart]) }.maxByOrNull { it.key } ?: continue
            if (i >= hours.size) break
            val next = present.getValue(hours[i])
            var sum = prev.value
            for (h in hours.subList(runStart, i)) {
                val n = steps[h] ?: continue
                sum = minOf(sum + n, next)
                rows += StatRow(h, linkedMapOf<String, Number>("sum" to sum))
            }
        }
        return rows
    }

    /** Heart rate: mean, min and max of the app's samples for each hour Home Assistant has no row for. */
    fun heartRate(samples: Map<Instant, List<Int>>, present: Set<Instant>): List<StatRow> =
        samples.toSortedMap().filter { (t, v) -> v.isNotEmpty() && t !in present }.map { (t, v) ->
            StatRow(t, linkedMapOf<String, Number>("mean" to Math.round(v.average() * 10) / 10.0, "min" to v.min(), "max" to v.max()))
        }

    fun utcHour(t: LocalDateTime, zone: ZoneId): Instant = t.atZone(zone).toInstant().truncatedTo(ChronoUnit.HOURS)

    /** "2026-11-24T09:00:00+00:00", as Home Assistant reads it. */
    fun iso(t: Instant): String = t.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().let { if (it.length == 16) "$it:00" else it } + "+00:00"
}

/**
 * Fills the hours Home Assistant's own statistics lack (phone off, watch away, Home Assistant down) from the app's
 * history: asks what it has (`recorder/statistics_during_period` on the statistic of the live sensor) and imports
 * only the missing hours into that same statistic (`recorder/import_statistics`, source "recorder": Home Assistant
 * allows importing into an entity's statistic, so the graphs show one series, no second id).
 */
class StatsSync(
    private val host: PluginHost,
    private val channel: () -> HaChannel?,
    private val log: (String) -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
) {
    /** Unique id to entity id, from Home Assistant's entity registry. */
    private val entityIds = HashMap<String, String>()

    /** Checks the last [days] days of [watch]; returns the number of hours filled, or -1 when it could not check. */
    suspend fun sync(watch: PairedWatch, days: Long = GAP_DAYS): Int {
        if (Capability.HEALTH_LOG !in watch.capabilities) return 0
        val ch = channel()?.takeIf { it.ready } ?: return -1
        val z = zone()
        val w = watch.id
        val node = Topics.node(w)
        val stepsEntity = entityId(ch, "smartsync_${node}_steps_today")
        val hrEntity = entityId(ch, "smartsync_${node}_heart_rate")
        if (stepsEntity == null && hrEntity == null) {
            log("Statistics: the watch's sensors are not in Home Assistant yet")
            return -1
        }
        val lastStart = now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS)
        val windowStart = lastStart.minus(days * 24, ChronoUnit.HOURS)
        val hoursOfWindow = generateSequence(windowStart) { it.plus(1, ChronoUnit.HOURS) }.takeWhile { !it.isAfter(lastStart) }.toList()
        val range = HistoryRange(LocalDateTime.ofInstant(windowStart, z), LocalDateTime.ofInstant(lastStart.plus(1, ChronoUnit.HOURS).minusSeconds(1), z))

        val reply = ch.call(
            mapOf(
                "type" to "recorder/statistics_during_period", "start_time" to StatsPlan.iso(windowStart),
                "end_time" to StatsPlan.iso(lastStart.plus(1, ChronoUnit.HOURS)),
                "statistic_ids" to listOfNotNull(stepsEntity, hrEntity), "period" to "hour", "types" to listOf("sum", "mean"),
            ),
        )
        if (!reply.success) {
            log("Statistics: Home Assistant did not answer the gap check (${reply.code})")
            return -1
        }
        val present = presentRows(reply.result)
        var filled = 0

        if (stepsEntity != null) {
            val hours = host.history(w, DataKind.Hours, range).valueOrNull()
            val steps = hours.orEmpty().filter { it.steps != null }.groupBy { StatsPlan.utcHour(it.start, z) }
                .mapValues { (_, l) -> l.sumOf { it.steps!! } }
            val have = present[stepsEntity].orEmpty().mapNotNull { (t, v) -> (v["sum"] as? Number)?.let { t to it.toDouble() } }.toMap()
            val rows = StatsPlan.steps(hoursOfWindow, steps, have)
            if (rows.isNotEmpty() && import(ch, stepsEntity, "steps", hasSum = true, meanType = 0, rows)) filled += rows.size
        }
        if (hrEntity != null) {
            val samples = host.history(w, DataKind.HeartRate, range).valueOrNull().orEmpty()
            val hr = samples.groupBy { StatsPlan.utcHour(it.time, z) }.mapValues { (_, l) -> l.map { it.bpm } }
                .filterKeys { !it.isBefore(windowStart) && !it.isAfter(lastStart) }
            val rows = StatsPlan.heartRate(hr, present[hrEntity].orEmpty().keys)
            if (rows.isNotEmpty() && import(ch, hrEntity, "bpm", hasSum = false, meanType = 1, rows)) filled += rows.size
        }
        if (filled > 0) log("Statistics: filled $filled hours Home Assistant had missed")
        return filled
    }

    private suspend fun entityId(ch: HaChannel, uniqueId: String): String? {
        entityIds[uniqueId]?.let { return it }
        val r = ch.call(mapOf("type" to "config/entity_registry/list"))
        if (!r.success) return null
        (r.result as? List<*>).orEmpty().forEach { e ->
            val m = e as? Map<*, *> ?: return@forEach
            val u = m["unique_id"] as? String ?: return@forEach
            val id = m["entity_id"] as? String ?: return@forEach
            if (m["platform"] == "mqtt" && u.startsWith("smartsync_")) entityIds[u] = id
        }
        return entityIds[uniqueId]
    }

    private suspend fun import(ch: HaChannel, id: String, unit: String, hasSum: Boolean, meanType: Int, rows: List<StatRow>): Boolean {
        for (chunk in rows.chunked(CHUNK)) {
            val r = ch.call(importMessage(id, unit, hasSum, meanType, chunk))
            if (!r.success) {
                log("Statistics: import refused (${r.code})")
                return false
            }
        }
        return true
    }

    companion object {
        const val GAP_DAYS = 7L
        const val CHUNK = 500

        fun importMessage(entityId: String, unit: String, hasSum: Boolean, meanType: Int, rows: List<StatRow>): Map<String, Any?> = mapOf(
            "type" to "recorder/import_statistics",
            "metadata" to linkedMapOf(
                "has_sum" to hasSum, "mean_type" to meanType, "name" to null, "source" to "recorder",
                "statistic_id" to entityId, "unit_class" to null, "unit_of_measurement" to unit,
            ),
            "stats" to rows.map { r -> linkedMapOf<String, Any?>("start" to StatsPlan.iso(r.start)) + r.values },
        )

        /** Statistic id to its rows by hour start (Home Assistant's `start` is epoch milliseconds or an ISO time). */
        fun presentRows(result: Any?): Map<String, Map<Instant, Map<*, *>>> {
            val m = result as? Map<*, *> ?: return emptyMap()
            return m.entries.associate { (k, v) ->
                k.toString() to (v as? List<*>).orEmpty().mapNotNull { row ->
                    val r = row as? Map<*, *> ?: return@mapNotNull null
                    val t = when (val s = r["start"]) {
                        is Number -> Instant.ofEpochMilli(s.toLong())
                        is String -> runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
                        else -> null
                    }?.truncatedTo(ChronoUnit.HOURS) ?: return@mapNotNull null
                    t to r
                }.toMap()
            }
        }
    }
}
