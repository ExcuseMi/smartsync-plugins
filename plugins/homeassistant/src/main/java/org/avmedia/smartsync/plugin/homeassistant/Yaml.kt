package org.avmedia.smartsync.plugin.homeassistant

/** A dashboard config as YAML for Home Assistant's raw configuration editor. Only what a dashboard uses. */
object Yaml {
    fun dump(value: Map<String, Any?>): String = StringBuilder().also { write(it, value, 0) }.toString()

    private fun write(out: StringBuilder, e: Any?, indent: Int) {
        val pad = "  ".repeat(indent)
        when (e) {
            is Map<*, *> -> e.forEach { (k, v) ->
                when {
                    v is Map<*, *> && v.isNotEmpty() -> { out.append(pad).append(k).append(":\n"); write(out, v, indent + 1) }
                    v is List<*> && v.isNotEmpty() -> { out.append(pad).append(k).append(":\n"); write(out, v, indent) }
                    else -> out.append(pad).append(k).append(": ").append(scalar(v)).append('\n')
                }
            }
            is List<*> -> e.forEach { item ->
                if (item is Map<*, *> && item.isNotEmpty()) {
                    val inner = StringBuilder().also { write(it, item, indent + 1) }.toString().lines().filter { it.isNotEmpty() }
                    inner.forEachIndexed { i, line ->
                        out.append(if (i == 0) pad + "- " + line.removePrefix("  ".repeat(indent + 1)) else line).append('\n')
                    }
                } else out.append(pad).append("- ").append(scalar(item)).append('\n')
            }
            else -> out.append(pad).append(scalar(e)).append('\n')
        }
    }

    private fun scalar(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        is Number, is Boolean -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String =
        if (Regex("[A-Za-z0-9_./-][A-Za-z0-9_./ -]*").matches(s) && s.lowercase() !in RESERVED && s.toDoubleOrNull() == null) s
        else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val RESERVED = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
}
