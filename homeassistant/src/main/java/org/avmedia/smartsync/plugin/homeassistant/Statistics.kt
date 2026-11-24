package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.Capability
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.WatchId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** One hour of a statistic: [start] is a UTC hour start, [values] the fields Home Assistant takes (sum, state, mean, ...). */
data class StatRow(val start: Instant, val values: Map<String, Number>)

/** The statistic ids of a watch: external statistics of source "smartsync", one per watch and kind. */
object StatIds {
    const val SOURCE = "smartsync"

    fun obj(w: WatchId): String = w.key.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")

    fun steps(w: WatchId) = "$SOURCE:${obj(w)}_steps_hourly"

    fun heartRate(w: WatchId) = "$SOURCE:${obj(w)}_heart_rate_hourly"
}

/** Which hours to import: only those Home Assistant lacks, with sums that do not depend on what it has. */
object StatsPlan {
    /**
     * Steps: [hours] are finished UTC hours with their steps (oldest first), [base] the steps of every hour before
     * them since the watch's origin. The sum of an hour is always base plus the steps up to it, from the app's own
     * history, so an hour imported late (Home Assistant was down) fits between the ones it already has.
     */
    fun steps(hours: Map<Instant, Int>, base: Long, present: Set<Instant>): List<StatRow> {
        var running = base
        val rows = ArrayList<StatRow>()
        for ((t, n) in hours.toSortedMap()) {
            running += n
            if (t == t) rows += StatRow(t, linkedMapOf<String, Number>("state" to n, "sum" to running))
        }
        return rows
    }

    /** Heart rate: mean, min and max of each finished UTC hour's samples. */
    fun heartRate(samples: Map<Instant, List<Int>>, present: Set<Instant>): List<StatRow> =
        samples.toSortedMap().filter { (t, v) -> v.isNotEmpty() && t !in present }.map { (t, v) ->
            StatRow(t, linkedMapOf<String, Number>("mean" to Math.round(v.average() * 10) / 10.0, "min" to v.min(), "max" to v.max()))
        }

    fun utcHour(t: LocalDateTime, zone: ZoneId): Instant = t.atZone(zone).toInstant().truncatedTo(ChronoUnit.HOURS)

    /** "2026-11-24T09:00:00+00:00", as Home Assistant reads it. */
    fun iso(t: Instant): String = t.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().let { if (it.length == 16) "$it:00" else it } + "+00:00"
}

/**
 * Imports each watch's finished hours into Home Assistant's long-term statistics (`recorder/import_statistics`) and
 * fills gaps: asks what Home Assistant has (`recorder/statistics_during_period`) and imports only the missing hours,
 * from the app's history. The current hour stays in the live sensors.
 */
class StatsSync(
    private val host: PluginHost,
    private val channel: () -> HaChannel?,
    private val log: (String) -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
) {
    /** Checks the last [days] days of [watch]; returns the number of rows imported, or -1 when it could not check. */
    suspend fun sync(watch: PairedWatch, days: Long = GAP_DAYS): Int {
        if (Capability.HEALTH_LOG !in watch.capabilities) return 0
        val ch = channel()?.takeIf { it.ready } ?: return -1
        val z = zone()
        val w = watch.id
        val lastStart = now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS)
        val windowStart = lastStart.minus(days * 24, ChronoUnit.HOURS)
        val store = host.storage()
        val originKey = ORIGIN_KEY + Topics.node(w)
        val origin = store.get(originKey)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: windowStart.also { store.put(originKey, it.toString()) }
        val from = minOf(origin, windowStart)
        val end = lastStart.plus(1, ChronoUnit.HOURS).minusSeconds(1)

        val hours = host.history(w, DataKind.Hours, HistoryRange(local(from, z), local(end, z))).valueOrNull()
            ?: return (-1).also { log("Statistics: could not read the hours of the history") }
        val byHour = hours.filter { it.steps != null }.groupBy { StatsPlan.utcHour(it.start, z) }
            .mapValues { (_, l) -> l.sumOf { it.steps!! } }.filterKeys { !it.isAfter(lastStart) }
        val base = byHour.filterKeys { it.isBefore(windowStart) }.values.sumOf { it.toLong() }
        val window = byHour.filterKeys { !it.isBefore(windowStart) }

        val samples = host.history(w, DataKind.HeartRate, HistoryRange(local(windowStart, z), local(end, z))).valueOrNull().orEmpty()
        val hr = samples.groupBy { StatsPlan.utcHour(it.time, z) }.mapValues { (_, l) -> l.map { it.bpm } }
            .filterKeys { !it.isBefore(windowStart) && !it.isAfter(lastStart) }

        val ids = listOf(StatIds.steps(w), StatIds.heartRate(w))
        val reply = ch.call(
            mapOf(
                "type" to "recorder/statistics_during_period", "start_time" to StatsPlan.iso(windowStart),
                "end_time" to StatsPlan.iso(lastStart.plus(1, ChronoUnit.HOURS)), "statistic_ids" to ids, "period" to "hour",
                "types" to listOf("sum", "mean"),
            ),
        )
        if (!reply.success) {
            log("Statistics: Home Assistant did not answer the gap check (${reply.code})")
            return -1
        }
        val present = presentHours(reply.result)
        val name = watch.name.ifBlank { w.key }
        var imported = 0
        val stepRows = StatsPlan.steps(window, base, present[ids[0]].orEmpty())
        if (stepRows.isNotEmpty() && import(ch, ids[0], "$name steps per hour", "steps", hasSum = true, meanType = 0, stepRows)) imported += stepRows.size
        val hrRows = StatsPlan.heartRate(hr, present[ids[1]].orEmpty())
        if (hrRows.isNotEmpty() && import(ch, ids[1], "$name heart rate per hour", "bpm", hasSum = false, meanType = 1, hrRows)) imported += hrRows.size
        if (imported > 0) log("Statistics: imported $imported hours")
        return imported
    }

    private suspend fun import(ch: HaChannel, id: String, name: String, unit: String, hasSum: Boolean, meanType: Int, rows: List<StatRow>): Boolean {
        for (chunk in rows.chunked(CHUNK)) {
            val r = ch.call(importMessage(id, name, unit, hasSum, meanType, chunk))
            if (!r.success) {
                log("Statistics: import refused (${r.code})")
                return false
            }
        }
        return true
    }

    private fun local(t: Instant, z: ZoneId): LocalDateTime = LocalDateTime.ofInstant(t, z)

    companion object {
        const val GAP_DAYS = 7L
        const val CHUNK = 500
        const val ORIGIN_KEY = "stats.origin."

        fun importMessage(id: String, name: String, unit: String, hasSum: Boolean, meanType: Int, rows: List<StatRow>): Map<String, Any?> = mapOf(
            "type" to "recorder/import_statistics",
            "metadata" to linkedMapOf(
                "has_sum" to hasSum, "mean_type" to meanType, "name" to name, "source" to StatIds.SOURCE,
                "statistic_id" to id, "unit_class" to null, "unit_of_measurement" to unit,
            ),
            "stats" to rows.map { r -> linkedMapOf<String, Any?>("start" to StatsPlan.iso(r.start)) + r.values },
        )

        /** Statistic id to the hour starts Home Assistant has (its `start` is epoch milliseconds or an ISO time). */
        fun presentHours(result: Any?): Map<String, Set<Instant>> {
            val m = result as? Map<*, *> ?: return emptyMap()
            return m.entries.associate { (k, v) ->
                k.toString() to (v as? List<*>).orEmpty().mapNotNull { row ->
                    when (val s = (row as? Map<*, *>)?.get("start")) {
                        is Number -> Instant.ofEpochMilli(s.toLong())
                        is String -> runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
                        else -> null
                    }?.truncatedTo(ChronoUnit.HOURS)
                }.toSet()
            }
        }
    }
}
