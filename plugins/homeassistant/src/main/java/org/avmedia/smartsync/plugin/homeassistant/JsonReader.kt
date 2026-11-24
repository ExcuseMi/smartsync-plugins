package org.avmedia.smartsync.plugin.homeassistant

/**
 * A small JSON reader: objects become maps (in order), arrays lists, numbers Long or Double. Own code for the same
 * reason as [Json]. Null for text that is not one JSON value.
 */
object JsonReader {
    fun read(text: String): Any? = runCatching { Parser(text).let { p -> p.value().also { p.end() } } }.getOrNull()

    /** [text] as an object, null when it is anything else. */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?>? = read(text) as? Map<String, Any?>

    private class Parser(val s: String) {
        var i = 0

        fun end() { ws(); require(i == s.length) }

        fun value(): Any? {
            ws()
            require(i < s.length)
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected $c")
            }
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                require(peek() == '"')
                val k = str()
                ws(); require(peek() == ':'); i++
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("object")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("array")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                require(i < s.length)
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, i))
            i += w.length
            return v
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("end")

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    }
}
