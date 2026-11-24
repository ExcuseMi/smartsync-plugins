package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.ConnectionKind
import org.avmedia.smartsync.plugin.api.DataKind
import org.avmedia.smartsync.plugin.api.HistoryRange
import org.avmedia.smartsync.plugin.api.ValueCause
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class EventsTest {
    private val a = WatchId("AA:01")
    private val b = WatchId("BB:02")
    private val names = mapOf(a to "CASIO GBX-H5600", b to "CASIO GW-B5600")
    private val mapper = EventMapper(ZoneOffset.UTC)
    private fun map(e: WatchEvent) = mapper.map(e) { names[it] }
    private fun cfg(kind: EventKind, t: Long? = null, watch: WatchChoice = WatchChoice.Connected) = EventConfig(kind, watch, t)
    private fun values(w: WatchId, vararg kv: Pair<String, Any?>) =
        WatchEvent.ValuesUpdated(w, WatchValues(w, kv.toMap()), ValueCause.CONNECTION)

    @Test fun aButtonPressMatchesOnlyButtonEventsOfItsWatch() {
        val f = map(WatchEvent.ButtonPressed(a, Instant.parse("2026-11-24T10:15:00Z")))!!
        assertEquals("CASIO GBX-H5600", f.variables[Vars.WATCH])
        assertEquals("2026-11-24T10:15Z", f.variables[Vars.TIME])
        assertTrue(EventMapper.matches(cfg(EventKind.BUTTON), f))
        assertTrue(EventMapper.matches(cfg(EventKind.BUTTON, watch = WatchChoice.Specific(a, "x")), f))
        assertFalse(EventMapper.matches(cfg(EventKind.BUTTON, watch = WatchChoice.Specific(b, "x")), f))
        assertFalse(EventMapper.matches(cfg(EventKind.CONNECTED), f))
        assertTrue("matching keys stay out of the variables", f.variables.keys.all { it.startsWith("%gs_") })
    }

    @Test fun connectAndDisconnectAreTheirOwnEvents() {
        val on = map(WatchEvent.Connected(a, ConnectionKind.USER))!!
        assertEquals("user", on.variables[Vars.CONNECTION])
        assertTrue(EventMapper.matches(cfg(EventKind.CONNECTED), on))
        assertFalse(EventMapper.matches(cfg(EventKind.DISCONNECTED), on))
        val off = map(WatchEvent.Disconnected(a))!!
        assertTrue(EventMapper.matches(cfg(EventKind.DISCONNECTED), off))
        assertFalse(EventMapper.matches(cfg(EventKind.CONNECTED), off))
    }

    @Test fun heartRateAboveFiresOncePerCrossing() {
        val above = cfg(EventKind.HR_ABOVE, 120)
        assertFalse(EventMapper.matches(above, map(values(a, ValueKeys.HR to 110L))!!))
        val up = map(values(a, ValueKeys.HR to 130L))!!
        assertTrue(EventMapper.matches(above, up))
        assertEquals("130", up.variables[Vars.HR])
        assertFalse("still above: no second event", EventMapper.matches(above, map(values(a, ValueKeys.HR to 135L))!!))
        assertFalse("at the level is not above", EventMapper.matches(cfg(EventKind.HR_ABOVE, 135), map(values(a, ValueKeys.HR to 135L))!!))
    }

    @Test fun heartRateBelowAndBatteryBelowFireWhenTheyDrop() {
        map(values(a, ValueKeys.HR to 70L, ValueKeys.BATTERY to 25L))
        val f = map(values(a, ValueKeys.HR to 55L, ValueKeys.BATTERY to 15L))!!
        assertTrue(EventMapper.matches(cfg(EventKind.HR_BELOW, 60), f))
        assertTrue(EventMapper.matches(cfg(EventKind.BATTERY_BELOW, 20), f))
        assertFalse(EventMapper.matches(cfg(EventKind.BATTERY_BELOW, 10), f))
        assertEquals("15", f.variables[Vars.BATTERY])
    }

    @Test fun theStepGoalFiresWhenReached() {
        val goal = cfg(EventKind.STEP_GOAL)
        assertFalse(EventMapper.matches(goal, map(values(a, ValueKeys.STEPS to 9_000L, ValueKeys.STEP_GOAL to 10_000L))!!))
        val reached = map(values(a, ValueKeys.STEPS to 10_050L))!!
        assertTrue(EventMapper.matches(goal, reached))
        assertEquals("10000", reached.variables[Vars.STEP_GOAL])
        assertEquals("10050", reached.variables[Vars.STEPS])
        assertFalse(EventMapper.matches(goal, map(values(a, ValueKeys.STEPS to 10_500L))!!))
    }

    @Test fun valuesFromBeforeARestartDoNotFireAgain() {
        mapper.seed(WatchValues(a, mapOf(ValueKeys.HR to 130L, ValueKeys.STEPS to 12_000L, ValueKeys.STEP_GOAL to 10_000L)))
        val f = map(values(a, ValueKeys.HR to 140L, ValueKeys.STEPS to 12_500L))!!
        assertFalse(EventMapper.matches(cfg(EventKind.HR_ABOVE, 120), f))
        assertFalse(EventMapper.matches(cfg(EventKind.STEP_GOAL), f))
    }

    @Test fun onlyValuesReadNowCount() {
        val stale = WatchEvent.ValuesUpdated(a, WatchValues(a, mapOf(ValueKeys.HR to 150L)), ValueCause.REQUEST, readNow = emptySet())
        assertNull(map(stale))
    }

    @Test fun aStoredWorkoutPassesItsNumbers() {
        val w = WorkoutRecord("w1", Instant.parse("2026-11-24T07:00:00Z"), sport = 1, sportName = "Run", totalSec = 1_830, distanceM = 5_012.4, kcal = 410, hrAvg = 151)
        val f = map(WatchEvent.WorkoutStored(a, w))!!
        assertTrue(EventMapper.matches(cfg(EventKind.WORKOUT), f))
        assertEquals("Run", f.variables[Vars.WORKOUT_SPORT])
        assertEquals("30", f.variables[Vars.WORKOUT_MINUTES])
        assertEquals("410", f.variables[Vars.WORKOUT_KCAL])
        assertEquals("151", f.variables[Vars.WORKOUT_HR_AVG])
        assertEquals("5012", f.variables[Vars.WORKOUT_DISTANCE_M])
    }

    @Test fun aHealthReadNamesItsKinds() {
        val r = HistoryRange.days(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24))
        val f = map(WatchEvent.HealthRead(a, r, setOf(DataKind.Hours, DataKind.HeartRate)))!!
        assertTrue(EventMapper.matches(cfg(EventKind.HEALTH_READ), f))
        assertEquals("hours,heart_rate", f.variables[Vars.HEALTH_KINDS])
    }

    @Test fun appEventsBecomeNothing() {
        assertNull(map(WatchEvent.BackgroundStarted))
        assertNull(map(WatchEvent.EventsLost))
    }

    @Test fun configsSurviveTheirBundle() {
        val c = EventConfig(EventKind.HR_ABOVE, WatchChoice.Specific(a, "CASIO GBX-H5600"), 120, state = true)
        assertEquals(c, EventConfig.fromMap(c.toMap()))
        assertEquals("Heart rate above 120 bpm, CASIO GBX-H5600", c.blurb())
        assertNull("a level is required", EventConfig.fromMap(c.toMap() - Keys.THRESHOLD))
        assertNull(EventConfig.fromMap(mapOf(Keys.KIND to "nope", Keys.WATCH to Keys.CONNECTED)))
    }

    @Test fun eachEventDescribesTheVariablesItSets() {
        EventKind.entries.forEach { k -> assertTrue(k.name, Vars.WATCH in Vars.of(k)) }
        assertTrue(Vars.HR in Vars.of(EventKind.HR_ABOVE))
        assertTrue(Vars.describe(listOf(Vars.HR)).single().startsWith("%gs_hr\n"))
    }
}
