package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.FailReason
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.avmedia.smartsync.plugin.api.WatchNotification

/** Which catalogue settings an action may offer for a watch. */
object SettingOffer {
    private fun isAlarmOrTimer(s: SettingInfo) = s.key.contains("alarm") || s.key.contains("timer")

    /** The rows of [catalog] (the chosen watch's own, [PluginHost.settingCatalog]) an action of [kind] lists. */
    fun offered(catalog: List<SettingInfo>, kind: ActionKind): List<SettingInfo> = when (kind) {
        ActionKind.SETTING -> catalog.filterNot(::isAlarmOrTimer)
        ActionKind.ALARM -> catalog.filter(::isAlarmOrTimer)
        else -> emptyList()
    }

    /** [raw] as [WatchCommand.ChangeSettings] takes it for [info], or why not. */
    fun parse(info: SettingInfo, raw: String): Result<Any> {
        val p = raw.trim()
        fun no(why: String) = Result.failure<Any>(IllegalArgumentException("${info.key}: $why"))
        if (p.isEmpty()) return no("empty value")
        return when (info.type) {
            SettingType.CHOICE -> info.choices.firstOrNull { it.equals(p, ignoreCase = true) }?.let { Result.success(it) }
                ?: no("one of " + info.choices.joinToString(", "))
            SettingType.BOOL -> when (p.lowercase()) {
                "on", "true", "1", "yes" -> Result.success(true)
                "off", "false", "0", "no" -> Result.success(false)
                else -> no("on or off")
            }
            SettingType.WHOLE -> {
                val n = p.toIntOrNull()
                val range = info.range
                val only = info.only
                when {
                    n == null -> no("a whole number")
                    range != null && n !in range -> no("${range.first} to ${range.last}")
                    only != null && n !in only -> no("one of " + only.sorted().joinToString(", "))
                    else -> Result.success(n)
                }
            }
            SettingType.TIME -> Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(p)?.let {
                Result.success(it.groupValues[1].padStart(2, '0') + ":" + it.groupValues[2])
            } ?: no("a time as HH:MM")
        }
    }
}

/** What an action did, as Tasker variables ([Vars.RESULT]: ok, queued or failed; [Vars.ERROR]). */
data class ActionOutcome(val ok: Boolean, val variables: Map<String, String>) {
    companion object {
        fun ok(delivered: Delivered) = ActionOutcome(true, mapOf(Vars.RESULT to if (delivered == Delivered.NOW) "ok" else "queued", Vars.ERROR to ""))
        fun failed(reason: FailReason, message: String) =
            ActionOutcome(false, mapOf(Vars.RESULT to "failed", Vars.ERROR to "${reason.name.lowercase()}: $message"))
    }
}

/**
 * Runs an action configuration through the host. Writes wait for the next connection when the watch is not connected
 * (a watch that connects by its button), so the result is "queued" then.
 */
class ActionRunner(private val host: PluginHost) {
    fun target(choice: WatchChoice): WatchId? = when (choice) {
        WatchChoice.Connected -> host.connected() ?: host.current()
        is WatchChoice.Specific -> host.watches().firstOrNull { it.id == choice.id }?.id
    }

    suspend fun run(config: ActionConfig): ActionOutcome {
        val watch = target(config.watch)
            ?: return ActionOutcome.failed(FailReason.INVALID, if (config.watch is WatchChoice.Specific) "${config.watch.label} is not paired" else "no watch paired")
        val result: PluginResult<Delivered> = when (config.kind) {
            ActionKind.NOTIFY -> host.notify(
                watch,
                WatchNotification(config.type, config.app.ifBlank { "Tasker" }, config.title, config.text),
                Delivery.NextConnection(),
            )
            ActionKind.SETTING, ActionKind.ALARM -> {
                val info = SettingOffer.offered(host.settingCatalog(watch), config.kind).firstOrNull { it.key == config.setting }
                    ?: return ActionOutcome.failed(FailReason.NOT_SUPPORTED, "${config.setting} is not on this watch")
                val value = SettingOffer.parse(info, config.value).getOrElse {
                    return ActionOutcome.failed(FailReason.INVALID, it.message ?: config.setting)
                }
                host.command(watch, WatchCommand.ChangeSettings(mapOf(info.key to value)), Delivery.NextConnection(key = "setting.${info.key}"))
            }
            ActionKind.READ -> host.command(watch, WatchCommand.RefreshValues, Delivery.NextConnection(key = "read"))
        }
        return when (result) {
            is PluginResult.Ok -> ActionOutcome.ok(result.value)
            is PluginResult.Failed -> ActionOutcome.failed(result.reason, result.message)
        }
    }
}
