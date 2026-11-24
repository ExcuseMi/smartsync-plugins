package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.NotificationKind
import org.avmedia.smartsync.plugin.api.WatchNotification

/**
 * A notification message on `<base>/<watch>/set/notify`: JSON with `message` and the optional `title`, `app` (the
 * source line the watch shows) and `type` (message, calendar, email, call, generic), or plain text (a first line and
 * more lines: the first is the title). `app` and `type` may also come in a `data` object, as the mobile app's notify
 * action had them; a top-level field wins.
 */
object NotifyPayload {
    const val MAX_TITLE = 60
    const val MAX_TEXT = 500
    const val MAX_APP = 20
    const val DEFAULT_APP = "Home Assistant"

    fun parse(payload: String): WatchNotification? {
        val t = payload.trim()
        if (t.isEmpty()) return null
        val json = if (t.startsWith("{")) JsonReader.obj(t) else null
        if (json != null) {
            val message = (json["message"] as? String)?.trim().orEmpty()
            val title = (json["title"] as? String)?.trim().orEmpty()
            if (message.isEmpty() && title.isEmpty()) return null
            val data = json["data"] as? Map<*, *>
            fun field(name: String): String? = sequenceOf(json[name], data?.get(name))
                .mapNotNull { (it as? String)?.trim()?.takeIf { v -> v.isNotEmpty() } }.firstOrNull()
            val app = field("app") ?: DEFAULT_APP
            val kind = NotificationKind.entries.firstOrNull { it.name.equals(field("type"), ignoreCase = true) }
                ?: NotificationKind.GENERIC
            return WatchNotification(kind, app.take(MAX_APP), title.take(MAX_TITLE), message.take(MAX_TEXT))
        }
        val lines = t.lines()
        val title = if (lines.size > 1) lines.first().trim() else ""
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else t
        return WatchNotification(NotificationKind.GENERIC, DEFAULT_APP, title.take(MAX_TITLE), body.take(MAX_TEXT))
    }
}
