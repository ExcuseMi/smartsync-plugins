package org.avmedia.smartsync.plugin.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.avmedia.smartsync.plugin.api.DayRecord
import org.avmedia.smartsync.plugin.api.HeartRateSample
import org.avmedia.smartsync.plugin.api.HourRecord
import org.avmedia.smartsync.plugin.api.NightRecord
import org.avmedia.smartsync.plugin.api.SleepStage
import org.avmedia.smartsync.plugin.api.WorkoutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RecordPlannerTest {
    private val planner = RecordPlanner(ZoneOffset.UTC)
    private fun t(s: String) = LocalDateTime.parse(s)
    private fun i(s: String) = Instant.parse(s)

    @Test fun stepsAreOneRecordPerHourAndAGapIsNeverZero() {
        val got = planner.steps(listOf(
            HourRecord(t("2026-11-24T08:00"), steps = 812),
            HourRecord(t("2026-11-24T09:00"), steps = null),
            HourRecord(t("2026-11-24T10:00"), steps = 0),
        ))
        assertEquals(listOf(PlannedSteps("steps/2026-11-24T08:00", i("2026-11-24T08:00:00Z"), i("2026-11-24T09:00:00Z"), 812)), got)
    }

    @Test fun heartRateIsOneRecordPerDayWithItsSamplesInOrder() {
        val got = planner.heartRate(listOf(
            HeartRateSample(t("2026-11-24T10:02"), 70),
            HeartRateSample(t("2026-11-24T10:00"), 64),
            HeartRateSample(t("2026-11-24T10:04"), 0),
            HeartRateSample(t("2026-11-25T00:01"), 55),
        ))
        assertEquals(2, got.size)
        val day = got[0] as PlannedHeartRate
        assertEquals("hr/2026-11-24", day.key)
        assertEquals(listOf(64L, 70L), day.samples.map { it.bpm })
        assertEquals(i("2026-11-24T10:00:00Z"), day.start)
        assertEquals("hr/2026-11-25", got[1].key)
    }

    @Test fun restingHeartRateAndOxygenOnlyForFinishedDays() {
        val got = planner.days(listOf(
            DayRecord(LocalDate.parse("2026-11-23"), hrRest = 52, spo2 = 97),
            DayRecord(LocalDate.parse("2026-11-24"), hrRest = 50, spo2 = 96),
        ), today = LocalDate.parse("2026-11-24"))
        assertEquals(listOf("rhr/2026-11-23", "spo2/2026-11-23"), got.map { it.key })
    }

    @Test fun sleepWithoutProvenStagesIsTheSessionAlone() {
        val night = NightRecord(
            LocalDate.parse("2026-11-24"), fellAsleep = t("2026-11-23T23:10"), wokeUp = t("2026-11-24T06:40"),
            stages = listOf(SleepStage(t("2026-11-23T23:10"), "light"), SleepStage(t("2026-11-24T01:00"), "deep")),
            stagesConfirmed = false,
        )
        val got = planner.night(night) as PlannedSleep
        assertEquals(i("2026-11-23T23:10:00Z"), got.start)
        assertEquals(i("2026-11-24T06:40:00Z"), got.end)
        assertTrue(got.stages.isEmpty())
    }

    @Test fun provenStagesLastUntilTheNextOneAndTheLastUntilWaking() {
        val night = NightRecord(
            LocalDate.parse("2026-11-24"), fellAsleep = t("2026-11-23T23:10"), wokeUp = t("2026-11-24T06:40"),
            stages = listOf(SleepStage(t("2026-11-24T01:00"), "Deep"), SleepStage(t("2026-11-23T23:10"), "light")),
            stagesConfirmed = true,
        )
        val got = planner.night(night) as PlannedSleep
        assertEquals(listOf(
            PlannedStage(i("2026-11-23T23:10:00Z"), i("2026-11-24T01:00:00Z"), SleepKind.LIGHT),
            PlannedStage(i("2026-11-24T01:00:00Z"), i("2026-11-24T06:40:00Z"), SleepKind.DEEP),
        ), got.stages)
    }

    @Test fun anUnknownStageDropsAllStagesButKeepsTheSession() {
        val night = NightRecord(
            LocalDate.parse("2026-11-24"), fellAsleep = t("2026-11-23T23:10"), wokeUp = t("2026-11-24T06:40"),
            stages = listOf(SleepStage(t("2026-11-23T23:10"), "light"), SleepStage(t("2026-11-24T01:00"), "stage 7")),
            stagesConfirmed = true,
        )
        assertTrue((planner.night(night) as PlannedSleep).stages.isEmpty())
    }

    @Test fun aNightWithoutBothEndsIsNotWritten() {
        assertNull(planner.night(NightRecord(LocalDate.parse("2026-11-24"), fellAsleep = t("2026-11-23T23:10"))))
    }

    @Test fun aWorkoutIsItsSessionAndEnergyOnlyWhenTheUnitIsProven() {
        val w = WorkoutRecord("w1", i("2026-11-24T07:00:00Z"), i("2026-11-24T07:45:00Z"), 1, "Run", kcal = 420, distance = 7.2)
        assertEquals(listOf(
            PlannedExercise("exercise/w1", w.start, w.end!!, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Run"),
            PlannedCalories("kcal/w1", w.start, w.end!!, 420.0),
        ), planner.workout(w))
        assertEquals(listOf("exercise/w1"), planner.workout(w.copy(unconfirmed = setOf("kcal"))).map { it.key })
    }

    @Test fun aWorkoutWithoutEndUsesItsDurationAndWithNeitherIsSkipped() {
        val w = WorkoutRecord("w2", i("2026-11-24T07:00:00Z"), null, 3, "Walk", totalSec = 600)
        assertEquals(i("2026-11-24T07:10:00Z"), (planner.workout(w)[0] as PlannedExercise).end)
        assertTrue(planner.workout(w.copy(totalSec = null)).isEmpty())
    }

    @Test fun sportNamesMapToExerciseTypes() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, SportTypes.exerciseType("Treadmill run"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, SportTypes.exerciseType("Indoor bike"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, SportTypes.exerciseType("Cycling"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, SportTypes.exerciseType("Pool swim"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, SportTypes.exerciseType("Sport 42"))
    }
}
