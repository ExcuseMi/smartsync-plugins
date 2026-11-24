package org.avmedia.smartsync.plugin.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.avmedia.smartsync.plugin.api.NotificationKind
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType

/** The edit screen a Locale host opens; it hands back the configuration bundle, its line and its variables. */
abstract class EditActivity : ComponentActivity() {
    internal var previous: Map<String, String> = emptyMap()
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previous = BundleCodec.toMap(intent?.getBundleExtra(Protocol.EXTRA_BUNDLE))
        val host = AndroidSignals.host
        val watches = host?.watches().orEmpty()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (host == null) Text("Turn on the Tasker plugin in Smart Sync to pick a watch.", color = MaterialTheme.colorScheme.error)
                        Editor(watches)
                    }
                }
            }
        }
    }

    @Composable
    protected abstract fun Editor(watches: List<PairedWatch>)

    internal fun save(config: Map<String, String>, blurb: String, variables: List<String>, waits: Boolean) {
        val result = Intent()
            .putExtra(Protocol.EXTRA_BUNDLE, BundleCodec.toBundle(config))
            .putExtra(Protocol.EXTRA_BLURB, blurb)
            .putExtra(Protocol.EXTRA_RELEVANT_VARIABLES, Vars.describe(variables))
        if (waits) result.putExtra(Protocol.EXTRA_REQUESTED_TIMEOUT, Protocol.ACTION_TIMEOUT_MS)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** The settings the chosen watch has: its own catalogue (for "connected watch" the one connected now or last). */
    internal fun catalogFor(choice: WatchChoice): List<SettingInfo> {
        val host = AndroidSignals.host ?: return emptyList()
        val id = ActionRunner(host).target(choice) ?: return emptyList()
        return host.settingCatalog(id)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun WatchPicker(watches: List<PairedWatch>, choice: WatchChoice, onChoice: (WatchChoice) -> Unit) {
    Heading("Watch")
    Choice("Connected watch", choice == WatchChoice.Connected) { onChoice(WatchChoice.Connected) }
    watches.forEach { w ->
        Choice(w.name, (choice as? WatchChoice.Specific)?.id == w.id) { onChoice(WatchChoice.Specific(w.id, w.name)) }
    }
}

private fun watchFrom(previous: Map<String, String>): WatchChoice =
    EventConfig.fromMap(previous)?.watch ?: ActionConfig.fromMap(previous)?.watch ?: WatchChoice.Connected

/** Tasker event screen. */
class EditEventActivity : EditActivity() {
    @Composable
    override fun Editor(watches: List<PairedWatch>) = EventEditor(watches, state = false)
}

/** Locale condition (Tasker state) screen. */
class EditStateActivity : EditActivity() {
    @Composable
    override fun Editor(watches: List<PairedWatch>) = EventEditor(watches, state = true)
}

@Composable
private fun EditActivity.EventEditor(watches: List<PairedWatch>, state: Boolean) {
    val old = remember { EventConfig.fromMap(previous) }
    var kind by remember { mutableStateOf(old?.kind ?: if (state) EventKind.CONNECTED else EventKind.BUTTON) }
    var watch by remember { mutableStateOf(old?.watch ?: watchFrom(previous)) }
    var threshold by remember { mutableStateOf(old?.threshold?.toString() ?: "") }
    Heading(if (state) "While" else "When")
    (if (state) EventKind.states else EventKind.entries).forEach { k -> Choice(k.label, kind == k) { kind = k } }
    val unit = kind.threshold
    if (unit != null) {
        OutlinedTextField(
            threshold, { threshold = it.filter(Char::isDigit).take(5) },
            label = { Text("Level ($unit)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    WatchPicker(watches, watch) { watch = it }
    val t = threshold.toLongOrNull()
    Button(onClick = {
        val c = EventConfig(kind, watch, if (unit != null) t else null, state)
        save(c.toMap(), c.blurb(), Vars.of(kind), waits = false)
    }, enabled = unit == null || t != null) { Text("Save") }
}

/** Locale setting (Tasker action) screen. */
class EditActionActivity : EditActivity() {
    @Composable
    override fun Editor(watches: List<PairedWatch>) = ActionEditor(watches)
}

@Composable
private fun EditActivity.ActionEditor(watches: List<PairedWatch>) {
    val old = remember { ActionConfig.fromMap(previous) }
    var kind by remember { mutableStateOf(old?.kind ?: ActionKind.NOTIFY) }
    var watch by remember { mutableStateOf(old?.watch ?: WatchChoice.Connected) }
    var title by remember { mutableStateOf(old?.title ?: "") }
    var text by remember { mutableStateOf(old?.text ?: "") }
    var app by remember { mutableStateOf(old?.app ?: "Tasker") }
    var type by remember { mutableStateOf(old?.type ?: NotificationKind.GENERIC) }
    var setting by remember { mutableStateOf(old?.setting ?: "") }
    var value by remember { mutableStateOf(old?.value ?: "") }
    Heading("Do")
    ActionKind.entries.forEach { k -> Choice(k.label, kind == k) { kind = k } }
    WatchPicker(watches, watch) { watch = it }
    var label = setting
    val ready = when (kind) {
        ActionKind.NOTIFY -> {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(text, { text = it }, label = { Text("Text") })
            OutlinedTextField(app, { app = it }, label = { Text("Label (source line)") }, singleLine = true)
            Heading("Type")
            NotificationKind.entries.forEach { k -> Choice(k.name.lowercase().replaceFirstChar(Char::uppercase), type == k) { type = k } }
            title.isNotBlank()
        }
        ActionKind.SETTING, ActionKind.ALARM -> {
            val offered = remember(watch, kind) { SettingOffer.offered(catalogFor(watch), kind) }
            Heading("Setting")
            if (offered.isEmpty()) Text(if (kind == ActionKind.ALARM) "This watch has no alarm or timer the app can set." else "No settings known for this watch yet: connect it once.")
            offered.forEach { s -> Choice(s.label, setting == s.key) { setting = s.key; value = "" } }
            val info = offered.firstOrNull { it.key == setting }
            if (info != null) {
                label = info.label
                ValueInput(info, value) { value = it }
            }
            info != null && value.isNotBlank()
        }
        ActionKind.READ -> true
    }
    Button(onClick = {
        val c = ActionConfig(kind, watch, title, text, app, type, setting, value)
        save(c.toMap(), c.blurb(label), Vars.ofAction, waits = true)
    }, enabled = ready) { Text("Save") }
}

@Composable
private fun ValueInput(info: SettingInfo, value: String, onValue: (String) -> Unit) {
    when (info.type) {
        SettingType.CHOICE -> info.choices.forEach { c -> Choice(c, value == c) { onValue(c) } }
        SettingType.BOOL -> listOf("on", "off").forEach { c -> Choice(c, value == c) { onValue(c) } }
        SettingType.WHOLE -> OutlinedTextField(
            value, onValue, singleLine = true,
            label = { Text(info.range?.let { "Value (${it.first} to ${it.last}) or a %variable" } ?: "Value or a %variable") },
        )
        SettingType.TIME -> OutlinedTextField(value, onValue, singleLine = true, label = { Text("Time HH:MM or a %variable") })
    }
}
