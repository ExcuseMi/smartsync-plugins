package org.avmedia.smartsync.plugin.api

/**
 * One measurement of a watch ([PluginHost.measurementCatalog]): a value the watch gathers (not a setting), with what
 * its family profile says. A plugin offers nothing for a measurement whose [supported] is false; null is not known
 * (offer it as before).
 */
data class MeasurementInfo(
    val id: String,
    val title: String,
    val unit: String?,
    /** "instant", "day_total", "per_night", "per_activity" or "status". */
    val kind: String,
    /** The value keys ([WatchValues]) that hold it. */
    val valueKeys: List<String>,
    val supported: Boolean?,
    /** "auto", "manual" (started on the watch) or "during_activity"; null when not known. */
    val gathering: String? = null,
    /** When it is gathered ("interval", "overnight", "after_waking", "daily", ...); null when not known. */
    val whenGathered: String? = null,
    /** What it needs, in words ("Heart rate measurement: Always or Only during activities", "3 nights in 28 days"). */
    val requirements: List<String> = emptyList(),
    /** The [SettingInfo.key]s the requirements read. */
    val requiredSettings: Set<String> = emptySet(),
    /** How to start it on the watch, one line a step; empty when it is automatic. */
    val steps: List<String> = emptyList(),
    /** Minutes after the event until the watch has it; null: with the next read. */
    val availableAfterMinutes: IntRange? = null,
)
