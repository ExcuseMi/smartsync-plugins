package org.avmedia.smartsync.plugin.healthconnect

import org.avmedia.smartsync.plugin.api.PluginStorage

/**
 * What was written for one watch: per record key the version and fingerprint. A record seen again unchanged is not
 * written; a changed one is written with the next version, so Health Connect replaces it instead of adding one.
 */
class WriteLedger(private val storage: PluginStorage) {

    /** The records of [planned] that are new or changed, each with the version to write. */
    fun pending(planned: List<Planned>): List<Pending> = planned.distinctBy { it.key }.mapNotNull { p ->
        val old = entry(p.key)
        val fp = p.fingerprint()
        when {
            old == null -> Pending(p, 0)
            old.second == fp -> null
            else -> Pending(p, old.first + 1)
        }
    }

    /** Call after Health Connect took [written]. */
    fun commit(written: List<Pending>) {
        written.forEach { storage.put(PREFIX + it.record.key, "${it.version}:${it.record.fingerprint()}") }
    }

    private fun entry(key: String): Pair<Long, String>? {
        val raw = storage.get(PREFIX + key) ?: return null
        val version = raw.substringBefore(':').toLongOrNull() ?: return null
        return version to raw.substringAfter(':')
    }

    companion object {
        const val PREFIX = "written."
    }
}
