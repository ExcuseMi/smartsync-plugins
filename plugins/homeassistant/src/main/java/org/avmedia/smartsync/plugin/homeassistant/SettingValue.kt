package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType

/** A command payload checked against one setting of the host's catalogue. */
sealed interface Checked {
    /** [value] in the form [org.avmedia.smartsync.plugin.api.WatchCommand.ChangeSettings] takes. */
    data class Ok(val value: Any) : Checked

    data class Refused(val reason: String) : Checked
}

/** Payloads to setting values and setting values to state payloads, for every [SettingType]. */
object SettingValue {
    const val ON = "ON"
    const val OFF = "OFF"
    const val TIME_PATTERN = "^([01][0-9]|2[0-3]):[0-5][0-9]$"
    private val TIME = Regex(TIME_PATTERN)
    private const val MAX_PAYLOAD = 200

    fun check(info: SettingInfo, payload: String): Checked {
        val p = payload.trim()
        if (p.isEmpty()) return Checked.Refused("empty value")
        if (p.length > MAX_PAYLOAD) return Checked.Refused("value too long")
        return when (info.type) {
            SettingType.CHOICE -> info.choices.firstOrNull { it == p }?.let { Checked.Ok(it) }
                ?: Checked.Refused("not one of: " + info.choices.joinToString(", "))
            SettingType.BOOL -> when (p.lowercase()) {
                "on", "true", "1" -> Checked.Ok(true)
                "off", "false", "0" -> Checked.Ok(false)
                else -> Checked.Refused("ON or OFF")
            }
            SettingType.WHOLE -> {
                val n = p.toIntOrNull() ?: p.toDoubleOrNull()?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) < Int.MAX_VALUE }?.toInt()
                when {
                    n == null -> Checked.Refused("a whole number")
                    info.range != null && n !in info.range!! -> Checked.Refused("allowed range: ${info.range!!.first} to ${info.range!!.last}")
                    info.only != null && n !in info.only!! -> Checked.Refused("one of: " + info.only!!.sorted().joinToString(", "))
                    else -> Checked.Ok(n)
                }
            }
            SettingType.TIME -> if (TIME.matches(p)) Checked.Ok(p) else Checked.Refused("a time as HH:MM")
        }
    }

    /** The state payload of a setting's value; null when there is none. */
    fun format(info: SettingInfo, value: Any?): String? = when {
        value == null -> null
        info.type == SettingType.BOOL -> when (value) {
            is Boolean -> if (value) ON else OFF
            else -> value.toString()
        }
        else -> Values.format(value)
    }
}

/** Value to state payload: numbers without a needless ".0", booleans as ON/OFF, lists and maps as JSON. */
object Values {
    fun format(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) SettingValue.ON else SettingValue.OFF
        is String -> value
        is Double, is Float -> Json.write(value)
        is Number -> value.toString()
        is Map<*, *>, is Iterable<*> -> Json.write(value)
        else -> value.toString()
    }
}
