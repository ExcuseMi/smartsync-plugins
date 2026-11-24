package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Statistics go to Home Assistant once per hour (only the missing ones); history topics carry the real times. */
class HistoryTest {
    private val zone = ZoneId.of("Europe/Brussels")
    private val now = Instant.parse("2026-11-24T08:30:00Z")
    private val host = FakeHost()
    private val node = "gshock-aabbccddeeff"
    private val gbx = host.add(node, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG)
    private val yesterday = LocalDate.of(2026, 11, 23)
    private val ch = FakeChannel()
    private val logs = mutableListOf<String>()
    private val stats = StatsSync(host, { ch }, { logs += it }, { zone }, { now })
    private val stepsId = "smartsync:gshock_aabbccddeeff_steps_hourly"
    private val hrId = "smartsync:gshock_aabbccddeeff_heart_rate_hourly"

    init {
        // Yesterday every hour, today up to 09:00 local (the hour that runs now is not finished).
        host.hours[gbx] = (0..23).map { HourRecord(yesterday.atTime(it, 0), steps = 100 * (it + 1)) } +
            (0..9).map { HourRecord(yesterday.plusDays(1).atTime(it, 0), steps = 10) }
        host.heartRate[gbx] = (0 until 30).map { HeartRateSample(yesterday.atTime(10, 0).plusMinutes(2L * it), 60 + it) }
    }

    private fun utc(local: LocalDateTime) = local.atZone(zone).toInstant()

    @Suppress("UNCHECKED_CAST")
    private fun rows(id: String) = ch.imports().filter { (it["metadata"] as Map<String, Any?>)["statistic_id"] == id }
        .flatMap { it["stats"] as List<Map<String, Any?>> }

    private fun pw() = host.watches().single()

    @Test fun onlyTheHoursHomeAssistantLacksAreImportedWithSumsFromTheAppsHistory() = runTest {
        // Home Assistant has every finished hour of the window except yesterday 10, 11 and 12 local.
        val missing = (10..12).map { utc(yesterday.atTime(it, 0)) }.toSet()
        val all = host.hours[gbx]!!.map { utc(it.start) }.filter { it.isBefore(Instant.parse("2026-11-24T08:00:00Z")) }
        ch.present[stepsId] = all.filter { it !in missing }.toMutableSet()
        ch.present[hrId] = mutableSetOf()

        stats.sync(pw())
        val steps = rows(stepsId)
        assertEquals(listOf("2026-11-23T09:00:00+00:00", "2026-11-23T10:00:00+00:00", "2026-11-23T11:00:00+00:00"), steps.map { it["start"] })
        // Local 10:00 is the 11th hour of yesterday: 100 + 200 + ... + 1100.
        assertEquals(6600L, (steps.first()["sum"] as Number).toLong())
        assertEquals(1100, steps.first()["state"])
        val hr = rows(hrId).single()
        assertEquals("2026-11-23T09:00:00+00:00", hr["start"])
        assertEquals(60 to 89, hr["min"] to hr["max"])
        assertEquals(74.5, hr["mean"])

        @Suppress("UNCHECKED_CAST")
        val meta = ch.imports().first()["metadata"] as Map<String, Any?>
        assertEquals(mapOf("has_sum" to true, "mean_type" to 0, "source" to "smartsync", "unit_class" to null), meta.filterKeys { it in setOf("has_sum", "mean_type", "source", "unit_class") })
        assertTrue(meta.containsKey("unit_class"))
    }

    @Test fun anHourAlreadyInHomeAssistantIsNotImportedAgain() = runTest {
        stats.sync(pw())
        val first = ch.imports().size
        assertTrue(first > 0)
        // Every finished hour once: yesterday 24 + today 00:00 to 08:00 local (09:00 runs now) = 33.
        assertEquals(33, rows(stepsId).size)
        assertTrue(rows(stepsId).all { (it["start"] as String).matches(Regex("""\d{4}-\d\d-\d\dT\d\d:00:00\+00:00""")) })
        assertEquals(0, stats.sync(pw()))
        assertEquals(first, ch.imports().size)
    }

    @Test fun nothingIsSentWhileHomeAssistantIsAway() = runTest {
        ch.isReady = false
        assertEquals(-1, stats.sync(pw()))
        assertTrue(ch.calls.isEmpty())
        ch.isReady = true
        assertEquals(33 + 1, stats.sync(pw()))
    }

    @Test fun aWatchWithoutHealthHasNoStatistics() = runTest {
        val ocw = host.add("gshock-112233445566", "CASIO OCW-S7000", Watches.OCW_CAPS, Watches.OCW_CATALOG)
        assertEquals(0, stats.sync(host.watches().first { it.id == ocw }))
        assertTrue(ch.calls.isEmpty())
    }

    @Test fun historyTopicsCarryTheRealTimes() {
        val topics = Topics("smartsync", "homeassistant")
        val w = WorkoutRecord("w1", Instant.parse("2026-11-23T06:00:00Z"), Instant.parse("2026-11-23T06:45:00Z"), 1, "Running", totalSec = 2700, hrAvg = 141)
        val out = HistoryTopics.build(topics, gbx, host.hours[gbx]!!.take(2), host.heartRate[gbx]!!, listOf(w), zone)
        assertEquals(
            """[{"t":"2026-11-23T00:00+01:00","v":100},{"t":"2026-11-23T01:00+01:00","v":200}]""",
            out["smartsync/$node/history/steps_hourly/2026-11-23"],
        )
        assertEquals("""[{"t":"2026-11-23T10:00+01:00","mean":74.5,"min":60,"max":89}]""", out["smartsync/$node/history/heart_rate_hourly/2026-11-23"])
        val wo = out.getValue("smartsync/$node/history/workout/w1")
        assertTrue(wo, wo.contains("\"start\":\"2026-11-23T07:00+01:00\"") && wo.contains("\"end\":\"2026-11-23T07:45+01:00\""))
        assertFalse(wo.contains("null"))
    }

    @Test fun aHistoryDayIsPublishedAgainOnlyWhenItChanged() = runTest {
        val link = FakeLink()
        val b = Bridge(host, link, Watches.config(), "0.1.0", {}, { zone }, { LocalDate.of(2026, 11, 24) })
        b.syncHistory(pw())
        val days = link.published.filter { it.topic.contains("/history/") }
        assertEquals(setOf("steps_hourly/2026-11-23", "steps_hourly/2026-11-24", "heart_rate_hourly/2026-11-23"), days.map { it.topic.substringAfter("/history/") }.toSet())
        assertTrue(days.all { it.retain })
        b.syncHistory(pw())
        assertEquals(days.size, link.published.count { it.topic.contains("/history/") })
        host.hours[gbx] = host.hours[gbx]!! + HourRecord(LocalDate.of(2026, 11, 24).atTime(10, 0), steps = 5)
        b.syncHistory(pw())
        assertEquals("smartsync/$node/history/steps_hourly/2026-11-24", link.published.last().topic)
        assertEquals(days.size + 1, link.published.count { it.topic.contains("/history/") })
        // A week later yesterday's topics are emptied.
        val later = Bridge(host, link, Watches.config(), "0.1.0", {}, { zone }, { LocalDate.of(2026, 12, 5) })
        later.syncHistory(pw())
        assertEquals("", link.last("smartsync/$node/history/steps_hourly/2026-11-23")?.payload)
    }
}
