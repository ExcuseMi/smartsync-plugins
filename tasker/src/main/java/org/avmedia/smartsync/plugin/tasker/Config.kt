package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.NotificationKind
import org.avmedia.smartsync.plugin.api.WatchId

/** Which watch a configuration is about. */
sealed interface WatchChoice {
    /** The watch connected now (or, for an action, the one connected last). */
    data object Connected : WatchChoice

    data class Specific(val id: WatchId, val name: String) : WatchChoice

    val label: String get() = when (this) {
        Connected -> "connected watch"
        is Specific -> name
    }
}

/** An event or state a Tasker profile can wait for. [threshold] names the number the person gives, null for none. */
enum class EventKind(val id: String, val label: String, val threshold: String? = null, val isState: Boolean = false) {
    BUTTON("button", "Watch button pressed"),
    CONNECTED("connected", "Watch connected", isState = true),
    DISCONNECTED("disconnected", "Watch disconnected"),
    STEP_GOAL("step_goal", "Step goal reached", isState = true),
    HR_ABOVE("hr_above", "Heart rate above", "bpm", isState = true),
    HR_BELOW("hr_below", "Heart rate below", "bpm", isState = true),
    WORKOUT("workout", "Workout stored"),
    BATTERY_BELOW("battery_below", "Watch battery below", "%", isState = true),
    HEALTH_READ("health_read", "Health read done");

    companion object {
        fun of(id: String?): EventKind? = entries.firstOrNull { it.id == id }
        val states: List<EventKind> get() = entries.filter { it.isState }
    }
}

/** Config bundle keys (strings only, so every host keeps them). */
object Keys {
    const val MODE = "gs.mode"
    const val KIND = "gs.kind"
    const val WATCH = "gs.watch"
    const val WATCH_NAME = "gs.watch_name"
    const val THRESHOLD = "gs.threshold"
    const val ACTION = "gs.action"
    const val TITLE = "gs.title"
    const val TEXT = "gs.text"
    const val APP = "gs.app"
    const val TYPE = "gs.type"
    const val SETTING = "gs.setting"
    const val VALUE = "gs.value"
    const val CONNECTED = "connected"
    const val MODE_EVENT = "event"
    const val MODE_STATE = "state"
}

private fun watchToMap(w: WatchChoice): Map<String, String> = when (w) {
    WatchChoice.Connected -> mapOf(Keys.WATCH to Keys.CONNECTED)
    is WatchChoice.Specific -> mapOf(Keys.WATCH to w.id.key, Keys.WATCH_NAME to w.name)
}

private fun watchFromMap(m: Map<String, String>): WatchChoice? = when (val w = m[Keys.WATCH]) {
    null -> null
    Keys.CONNECTED -> WatchChoice.Connected
    else -> WatchChoice.Specific(WatchId(w), m[Keys.WATCH_NAME] ?: w)
}

/** One event or state configuration of a Tasker profile. */
data class EventConfig(val kind: EventKind, val watch: WatchChoice, val threshold: Long? = null, val state: Boolean = false) {
    fun toMap(): Map<String, String> = buildMap {
        put(Keys.MODE, if (state) Keys.MODE_STATE else Keys.MODE_EVENT)
        put(Keys.KIND, kind.id)
        putAll(watchToMap(watch))
        threshold?.let { put(Keys.THRESHOLD, it.toString()) }
    }

    /** The line the host shows: "Heart rate above 120 bpm, connected watch". */
    fun blurb(): String {
        val t = if (kind.threshold != null && threshold != null) " $threshold ${kind.threshold}" else ""
        return "${kind.label}$t, ${watch.label}"
    }

    companion object {
        fun fromMap(m: Map<String, String>): EventConfig? {
            val kind = EventKind.of(m[Keys.KIND]) ?: return null
            val watch = watchFromMap(m) ?: return null
            val threshold = m[Keys.THRESHOLD]?.toLongOrNull()
            if (kind.threshold != null && threshold == null) return null
            return EventConfig(kind, watch, threshold, m[Keys.MODE] == Keys.MODE_STATE)
        }
    }
}

/** What an action does. */
enum class ActionKind(val id: String, val label: String) {
    NOTIFY("notify", "Send a notification"),
    SETTING("setting", "Change a watch setting"),
    ALARM("alarm", "Set an alarm or timer"),
    READ("read", "Read the watch values now");

    companion object {
        fun of(id: String?): ActionKind? = entries.firstOrNull { it.id == id }
    }
}

/** One action configuration of a Tasker task. Text fields may hold Tasker variables, filled in by the host. */
data class ActionConfig(
    val kind: ActionKind,
    val watch: WatchChoice,
    val title: String = "",
    val text: String = "",
    val app: String = "Tasker",
    val type: NotificationKind = NotificationKind.GENERIC,
    val setting: String = "",
    val value: String = "",
) {
    fun toMap(): Map<String, String> = buildMap {
        put(Keys.ACTION, kind.id)
        putAll(watchToMap(watch))
        when (kind) {
            ActionKind.NOTIFY -> {
                put(Keys.TITLE, title); put(Keys.TEXT, text); put(Keys.APP, app); put(Keys.TYPE, type.name)
                put(Protocol.BUNDLE_VARIABLE_REPLACE_KEYS, "${Keys.TITLE} ${Keys.TEXT} ${Keys.APP}")
            }
            ActionKind.SETTING, ActionKind.ALARM -> {
                put(Keys.SETTING, setting); put(Keys.VALUE, value)
                put(Protocol.BUNDLE_VARIABLE_REPLACE_KEYS, Keys.VALUE)
            }
            ActionKind.READ -> Unit
        }
    }

    fun blurb(settingLabel: String = setting): String = when (kind) {
        ActionKind.NOTIFY -> "Notify ${watch.label}: $title"
        ActionKind.SETTING, ActionKind.ALARM -> "Set $settingLabel to $value, ${watch.label}"
        ActionKind.READ -> "Read values now, ${watch.label}"
    }

    companion object {
        fun fromMap(m: Map<String, String>): ActionConfig? {
            val kind = ActionKind.of(m[Keys.ACTION]) ?: return null
            val watch = watchFromMap(m) ?: return null
            return ActionConfig(
                kind, watch,
                title = m[Keys.TITLE].orEmpty(),
                text = m[Keys.TEXT].orEmpty(),
                app = m[Keys.APP] ?: "Tasker",
                type = NotificationKind.entries.firstOrNull { it.name == m[Keys.TYPE] } ?: NotificationKind.GENERIC,
                setting = m[Keys.SETTING].orEmpty(),
                value = m[Keys.VALUE].orEmpty(),
            )
        }
    }
}
