package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchValues

/** Tasker states: whether a state configuration holds now, from the host's stores. */
object States {
    /** The answer to a state query: null = unknown (no watch, no value yet); [variables] as for the event. */
    data class Answer(val holds: Boolean?, val variables: Map<String, String>)

    fun query(config: EventConfig, watches: List<PairedWatch>, values: (WatchId) -> WatchValues): Answer {
        val target = when (val w = config.watch) {
            WatchChoice.Connected -> watches.firstOrNull { it.connected }
            is WatchChoice.Specific -> watches.firstOrNull { it.id == w.id }
        }
        if (target == null) return Answer(if (config.kind == EventKind.CONNECTED) false else null, emptyMap())
        val v = values(target.id)
        val steps = whole(v[ValueKeys.STEPS])
        val goal = whole(v[ValueKeys.STEP_GOAL])
        val hr = whole(v[ValueKeys.HR])
        val battery = whole(v[ValueKeys.BATTERY])
        val vars = buildMap {
            put(Vars.WATCH, target.name); put(Vars.WATCH_ID, target.id.key)
            steps?.let { put(Vars.STEPS, it.toString()) }
            goal?.let { put(Vars.STEP_GOAL, it.toString()) }
            hr?.let { put(Vars.HR, it.toString()) }
            battery?.let { put(Vars.BATTERY, it.toString()) }
        }
        val t = config.threshold
        val holds: Boolean? = when (config.kind) {
            EventKind.CONNECTED -> target.connected
            EventKind.STEP_GOAL -> if (steps == null || goal == null || goal <= 0) null else steps >= goal
            EventKind.HR_ABOVE -> if (hr == null || t == null) null else hr > t
            EventKind.HR_BELOW -> if (hr == null || t == null) null else hr < t
            EventKind.BATTERY_BELOW -> if (battery == null || t == null) null else battery < t
            else -> null
        }
        return Answer(holds, vars)
    }
}
