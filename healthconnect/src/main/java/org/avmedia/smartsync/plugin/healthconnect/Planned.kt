package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.Capability
import java.security.MessageDigest
import java.time.Instant

/** The Health Connect record types this plugin writes, and the watch capability each needs. */
enum class RecordType(val capability: Capability) {
    STEPS(Capability.HEALTH_LOG),
    HEART_RATE(Capability.HEART_RATE),
    RESTING_HEART_RATE(Capability.HEART_RATE),
    OXYGEN_SATURATION(Capability.BLOOD_OXYGEN),
    SLEEP(Capability.SLEEP),
    EXERCISE(Capability.WORKOUTS),
    TOTAL_CALORIES(Capability.WORKOUTS),
    ;

    companion object {
        /** The types a watch with [capabilities] can fill. */
        fun offeredBy(capabilities: Set<Capability>): Set<RecordType> =
            entries.filter { it.capability in capabilities }.toSet()
    }
}

/**
 * One record to write, in plain values. [key] is stable for the same hour, day, night or workout, so a second read of
 * it maps to the same Health Connect record (clientRecordId) and becomes an update.
 */
sealed interface Planned {
    val type: RecordType
    val key: String

    /** Changes when any written value changes. */
    fun fingerprint(): String = sha1(toString())
}

data class PlannedSteps(override val key: String, val start: Instant, val end: Instant, val count: Long) : Planned {
    override val type get() = RecordType.STEPS
}

data class PlannedHeartRate(override val key: String, val samples: List<BpmSample>) : Planned {
    override val type get() = RecordType.HEART_RATE
    val start: Instant get() = samples.first().time
    val end: Instant get() = samples.last().time
}

data class BpmSample(val time: Instant, val bpm: Long)

data class PlannedRestingHeartRate(override val key: String, val time: Instant, val bpm: Long) : Planned {
    override val type get() = RecordType.RESTING_HEART_RATE
}

data class PlannedOxygen(override val key: String, val time: Instant, val percent: Double) : Planned {
    override val type get() = RecordType.OXYGEN_SATURATION
}

data class PlannedSleep(
    override val key: String,
    val start: Instant,
    val end: Instant,
    val stages: List<PlannedStage>,
) : Planned {
    override val type get() = RecordType.SLEEP
}

data class PlannedStage(val start: Instant, val end: Instant, val stage: SleepKind)

enum class SleepKind { AWAKE, LIGHT, DEEP, REM }

data class PlannedExercise(
    override val key: String,
    val start: Instant,
    val end: Instant,
    val exerciseType: Int,
    val title: String,
) : Planned {
    override val type get() = RecordType.EXERCISE
}

data class PlannedCalories(override val key: String, val start: Instant, val end: Instant, val kcal: Double) : Planned {
    override val type get() = RecordType.TOTAL_CALORIES
}

/** A record with the version it is written as (Health Connect keeps the highest version of a clientRecordId). */
data class Pending(val record: Planned, val version: Long)

internal fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
