package org.avmedia.smartsync.plugin.healthconnect

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Why the app asks for Health Connect permissions (Health Connect opens it from its permission screens). */
class RationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hc_rationale_title)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(R.string.hc_rationale_text)
            textSize = 16f
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
