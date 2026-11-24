package org.avmedia.smartsync.plugin.tasker

/**
 * The intent contract of Locale plugins with Tasker's additions (events, variables, completion), written out here
 * instead of using a library: the Tasker plugin library is GPL-3.0, this repository Apache-2.0. Names as the hosts
 * send and read them (Locale developer docs, Tasker's plugin developer page).
 */
object Protocol {
    private const val LOCALE = "com.twofortyfouram.locale.intent"
    private const val TASKER = "net.dinglisch.android.tasker"
    private const val TASKER_EXTRAS = "$TASKER.extras."

    const val ACTION_EDIT_SETTING = "$LOCALE.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "$LOCALE.action.FIRE_SETTING"
    const val ACTION_EDIT_CONDITION = "$LOCALE.action.EDIT_CONDITION"
    const val ACTION_QUERY_CONDITION = "$LOCALE.action.QUERY_CONDITION"
    const val ACTION_REQUEST_QUERY = "$LOCALE.action.REQUEST_QUERY"
    const val ACTION_EDIT_EVENT = "$TASKER.ACTION_EDIT_EVENT"

    /** The plugin's own configuration, a Bundle of strings. */
    const val EXTRA_BUNDLE = "$LOCALE.extra.BUNDLE"

    /** The one line the host shows for a configuration. */
    const val EXTRA_BLURB = "$LOCALE.extra.BLURB"

    /** REQUEST_QUERY: the class name of the edit screen whose configurations the host should query. */
    const val EXTRA_ACTIVITY = "$LOCALE.extra.ACTIVITY"

    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18

    /** Event data from REQUEST_QUERY, handed back in QUERY_CONDITION. */
    const val EXTRA_PASS_THROUGH = "${TASKER_EXTRAS}PASS_THROUGH_DATA"

    /** A Bundle of variables ("%gs_watch" to its value) in a result or a completion intent. */
    const val EXTRA_VARIABLES = "${TASKER_EXTRAS}VARIABLES"

    /** Config bundle key: space separated keys whose values the host fills with variables before a fire. */
    const val BUNDLE_VARIABLE_REPLACE_KEYS = "${TASKER_EXTRAS}VARIABLE_REPLACE_KEYS"

    /** Edit result: the variables the configuration sets, as "%name\nLabel\nDescription". */
    const val EXTRA_RELEVANT_VARIABLES = "$TASKER.RELEVANT_VARIABLES"

    /** Edit result: how long the host waits for a completion (ms). */
    const val EXTRA_REQUESTED_TIMEOUT = "${TASKER_EXTRAS}REQUESTED_TIMEOUT"

    /** FIRE_SETTING: the intent URI to send when an action is done. */
    const val EXTRA_COMPLETION_INTENT = "${TASKER_EXTRAS}COMPLETION_INTENT"
    const val EXTRA_RESULT_CODE = "${TASKER_EXTRAS}RESULT_CODE"

    /** Activity.RESULT_OK, RESULT_FIRST_USER and the one after it. */
    const val RESULT_OK = -1
    const val RESULT_FAILED = 1
    const val RESULT_PENDING = 2

    /** How long a host waits for an action to finish. */
    const val ACTION_TIMEOUT_MS = 60_000
}
