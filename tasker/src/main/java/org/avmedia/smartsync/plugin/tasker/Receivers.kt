package org.avmedia.smartsync.plugin.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.smartsync.plugin.api.FailReason
import timber.log.Timber

private const val TAG = "tasker"

private fun variables(vars: Map<String, String>): Bundle = BundleCodec.toBundle(vars)

/** FIRE_SETTING: runs an action, then reports [Vars.RESULT] and [Vars.ERROR] to the host. */
class FireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_FIRE_SETTING) return
        val config = ActionConfig.fromMap(BundleCodec.toMap(intent.getBundleExtra(Protocol.EXTRA_BUNDLE))) ?: return
        val completion = intent.getStringExtra(Protocol.EXTRA_COMPLETION_INTENT)
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        if (completion != null && ordered) pending.resultCode = Protocol.RESULT_PENDING
        if (completion != null) pending.finish()
        AndroidSignals.scope.launch {
            val outcome = withTimeoutOrNull(if (completion != null) 50_000L else 8_000L) {
                val host = AndroidSignals.awaitHost() ?: return@withTimeoutOrNull ActionOutcome.failed(FailReason.STOPPED, "turn on the Tasker plugin in Smart Sync")
                ActionRunner(host).run(config)
            } ?: ActionOutcome.failed(FailReason.TIMEOUT, "no answer in time")
            Timber.tag(TAG).i("action %s: %s", config.kind.id, outcome.variables[Vars.RESULT])
            val code = if (outcome.ok) Protocol.RESULT_OK else Protocol.RESULT_FAILED
            if (completion != null) {
                try {
                    val done = Intent.parseUri(completion, Intent.URI_INTENT_SCHEME)
                        .putExtra(Protocol.EXTRA_RESULT_CODE, code)
                        .putExtra(Protocol.EXTRA_VARIABLES, variables(outcome.variables))
                    context.sendBroadcast(done)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("completion not sent: %s", e.javaClass.simpleName)
                }
            } else {
                if (ordered) {
                    pending.resultCode = code
                    pending.getResultExtras(true).putBundle(Protocol.EXTRA_VARIABLES, variables(outcome.variables))
                }
                pending.finish()
            }
        }
    }
}

/** QUERY_CONDITION: whether an event configuration meets the event in the pass-through data, or a state holds now. */
class QueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_QUERY_CONDITION || !isOrderedBroadcast) return
        val config = EventConfig.fromMap(BundleCodec.toMap(intent.getBundleExtra(Protocol.EXTRA_BUNDLE)))
        if (config == null) { resultCode = Protocol.RESULT_CONDITION_UNKNOWN; return }
        val (code, vars) = if (!config.state) {
            val data = BundleCodec.toMap(intent.getBundleExtra(Protocol.EXTRA_PASS_THROUGH))
            val fired = Fired(data)
            if (data.isNotEmpty() && EventMapper.matches(config, fired)) Protocol.RESULT_CONDITION_SATISFIED to fired.variables
            else Protocol.RESULT_CONDITION_UNSATISFIED to emptyMap<String, String>()
        } else {
            val host = AndroidSignals.host
            val answer = host?.let { h -> States.query(config, h.watches()) { h.lastValues(it) } }
                ?: States.Answer(null, emptyMap())
            when (answer.holds) {
                true -> Protocol.RESULT_CONDITION_SATISFIED to answer.variables
                false -> Protocol.RESULT_CONDITION_UNSATISFIED to answer.variables
                null -> Protocol.RESULT_CONDITION_UNKNOWN to emptyMap<String, String>()
            }
        }
        resultCode = code
        if (vars.isNotEmpty()) getResultExtras(true).putBundle(Protocol.EXTRA_VARIABLES, variables(vars))
    }
}
