package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues
import java.time.ZoneId

/** The variables the plugin gives a Tasker task, with a label and one line each. */
object Vars {
    const val WATCH = "%gs_watch"
    const val WATCH_ID = "%gs_watch_id"
    const val TIME = "%gs_time"
    const val CONNECTION = "%gs_connection"
    const val STEPS = "%gs_steps"
    const val STEP_GOAL = "%gs_step_goal"
    const val HR = "%gs_hr"
    const val BATTERY = "%gs_battery"
    const val WORKOUT_SPORT = "%gs_workout_sport"
    const val WORKOUT_MINUTES = "%gs_workout_minutes"
    const val WORKOUT_KCAL = "%gs_workout_kcal"
    const val WORKOUT_HR_AVG = "%gs_workout_hr_avg"
    const val WORKOUT_DISTANCE_M = "%gs_workout_distance_m"
    const val WORKOUT_START = "%gs_workout_start"
    const val HEALTH_KINDS = "%gs_health_kinds"
    const val RESULT = "%gs_result"
    const val ERROR = "%gs_error"

    private val about = linkedMapOf(
        WATCH to "Watch name", WATCH_ID to "Watch id", TIME to "Time of the press", CONNECTION to "How it connected",
        STEPS to "Steps today", STEP_GOAL to "Step goal", HR to "Heart rate (bpm)", BATTERY to "Watch battery (%)",
        WORKOUT_SPORT to "Sport", WORKOUT_MINUTES to "Workout minutes", WORKOUT_KCAL to "Workout kcal",
        WORKOUT_HR_AVG to "Workout average heart rate", WORKOUT_DISTANCE_M to "Workout distance (m)",
        WORKOUT_START to "Workout start", HEALTH_KINDS to "Kinds of data read",
        RESULT to "ok, queued or failed", ERROR to "Why it failed, empty when it did not",
    )

    /** What an event of [kind] sets. */
    fun of(kind: EventKind): List<String> = listOf(WATCH, WATCH_ID) + when (kind) {
        EventKind.BUTTON -> listOf(TIME)
        EventKind.CONNECTED -> listOf(CONNECTION)
        EventKind.DISCONNECTED -> emptyList()
        EventKind.STEP_GOAL, EventKind.HR_ABOVE, EventKind.HR_BELOW, EventKind.BATTERY_BELOW -> listOf(STEPS, STEP_GOAL, HR, BATTERY)
        EventKind.WORKOUT -> listOf(WORKOUT_SPORT, WORKOUT_MINUTES, WORKOUT_KCAL, WORKOUT_HR_AVG, WORKOUT_DISTANCE_M, WORKOUT_START)
        EventKind.HEALTH_READ -> listOf(HEALTH_KINDS)
    }

    val ofAction: List<String> get() = listOf(RESULT, ERROR)

    /** Tasker's relevant-variables lines: "%gs_hr\nHeart rate (bpm)\n". */
    fun describe(names: List<String>): Array<String> = names.map { "$it\n${about[it] ?: it}\n" }.toTypedArray()
}

/** Value keys of the host's catalogue the plugin reads. */
object ValueKeys {
    const val STEPS = "health.steps_today"
    const val STEP_GOAL = "health.step_goal"
    const val HR = "health.hr_recent_avg"
    const val BATTERY = "watch.battery"
    val ALL = setOf(STEPS, STEP_GOAL, HR, BATTERY)
}

/** A whole number from a host value (Long, Double or text); null for none. */
internal fun whole(v: Any?): Long? = when (v) {
    is Number -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
    else -> null
}

/**
 * What one host event becomes for Tasker: the pass-through data of a REQUEST_QUERY (strings only). Variables are the
 * "%gs_" entries; the "gs." entries are for matching only.
 */
data class Fired(val data: Map<String, String>) {
    val variables: Map<String, String> get() = data.filterKeys { it.startsWith("%") }
}

/** Data keys of [Fired] used for matching. */
private object F {
    const val SOURCE = "gs.source"
    const val WATCH = "gs.watch"
    const val BEFORE = "gs.before."
    const val NOW = "gs.now."
}

/**
 * Turns host events into [Fired] items and decides which event configurations they meet. Keeps the last values per
 * watch, so a threshold event fires once per crossing, not at every read.
 */
class EventMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val last = HashMap<WatchId, Map<String, Long>>()

    /** The values known before this process started (from [org.avmedia.smartsync.plugin.api.PluginHost.lastValues]). */
    fun seed(values: WatchValues) {
        last[values.watch] = ValueKeys.ALL.mapNotNull { k -> whole(values[k])?.let { k to it } }.toMap()
    }

    fun map(event: WatchEvent, name: (WatchId) -> String?): Fired? {
        val w = event.watch ?: return null
        val base = mapOf(F.WATCH to w.key, Vars.WATCH to (name(w) ?: w.key), Vars.WATCH_ID to w.key)
        fun fired(source: String, extra: Map<String, String>) = Fired(base + (F.SOURCE to source) + extra)
        return when (event) {
            is WatchEvent.ButtonPressed -> fired(EventKind.BUTTON.id, mapOf(Vars.TIME to event.at.atZone(zone).toOffsetDateTime().toString()))
            is WatchEvent.Connected -> fired(EventKind.CONNECTED.id, mapOf(Vars.CONNECTION to event.kind.name.lowercase()))
            is WatchEvent.Disconnected -> fired(EventKind.DISCONNECTED.id, emptyMap())
            is WatchEvent.WorkoutStored -> {
                val o = event.workout
                fired(EventKind.WORKOUT.id, buildMap {
                    put(Vars.WORKOUT_SPORT, o.sportName)
                    put(Vars.WORKOUT_START, o.start.atZone(zone).toOffsetDateTime().toString())
                    o.totalSec?.let { put(Vars.WORKOUT_MINUTES, (it / 60).toString()) }
                    o.kcal?.let { put(Vars.WORKOUT_KCAL, it.toString()) }
                    o.hrAvg?.let { put(Vars.WORKOUT_HR_AVG, it.toString()) }
                    o.distanceM?.let { put(Vars.WORKOUT_DISTANCE_M, Math.round(it).toString()) }
                })
            }
            is WatchEvent.HealthRead -> fired(EventKind.HEALTH_READ.id, mapOf(Vars.HEALTH_KINDS to event.kinds.joinToString(",") { it.name }))
            is WatchEvent.ValuesUpdated -> {
                val before = last[w].orEmpty()
                val now = event.fresh().mapNotNull { (k, v) -> if (k in ValueKeys.ALL) whole(v)?.let { k to it } else null }.toMap()
                if (now.isEmpty()) return null
                last[w] = before + now
                val all = before + now
                fired(SOURCE_VALUES, buildMap {
                    all[ValueKeys.STEPS]?.let { put(Vars.STEPS, it.toString()) }
                    all[ValueKeys.STEP_GOAL]?.let { put(Vars.STEP_GOAL, it.toString()) }
                    all[ValueKeys.HR]?.let { put(Vars.HR, it.toString()) }
                    all[ValueKeys.BATTERY]?.let { put(Vars.BATTERY, it.toString()) }
                    now.forEach { (k, v) -> put(F.NOW + k, v.toString()) }
                    before.forEach { (k, v) -> put(F.BEFORE + k, v.toString()) }
                })
            }
            else -> null
        }
    }

    companion object {
        const val SOURCE_VALUES = "values"

        /** Whether [fired] meets event configuration [config]. */
        fun matches(config: EventConfig, fired: Fired): Boolean {
            if (config.kind != EventKind.WORKOUT) return false // red-first stub
            val d = fired.data
            val watch = config.watch
            if (watch is WatchChoice.Specific && d[F.WATCH] != watch.id.key) return false
            val source = d[F.SOURCE] ?: return false
            if (source != SOURCE_VALUES) return source == config.kind.id
            fun now(k: String) = d[F.NOW + k]?.toLongOrNull()
            fun before(k: String) = d[F.BEFORE + k]?.toLongOrNull()
            val t = config.threshold
            return when (config.kind) {
                EventKind.HR_ABOVE -> crossedUp(now(ValueKeys.HR), before(ValueKeys.HR), t ?: return false, strict = true)
                EventKind.HR_BELOW -> crossedDown(now(ValueKeys.HR), before(ValueKeys.HR), t ?: return false)
                EventKind.BATTERY_BELOW -> crossedDown(now(ValueKeys.BATTERY), before(ValueKeys.BATTERY), t ?: return false)
                EventKind.STEP_GOAL -> {
                    val goal = now(ValueKeys.STEP_GOAL) ?: before(ValueKeys.STEP_GOAL) ?: d[Vars.STEP_GOAL]?.toLongOrNull()
                    if (goal == null || goal <= 0) false else crossedUp(now(ValueKeys.STEPS), before(ValueKeys.STEPS), goal, strict = false)
                }
                else -> false
            }
        }

        /** [now] is over (or, not [strict], at) [t] and the value before was not. */
        private fun crossedUp(now: Long?, before: Long?, t: Long, strict: Boolean): Boolean {
            if (now == null) return false
            val over = { v: Long -> if (strict) v > t else v >= t }
            return over(now) && (before == null || !over(before))
        }

        private fun crossedDown(now: Long?, before: Long?, t: Long): Boolean =
            now != null && now < t && (before == null || before >= t)
    }
}
