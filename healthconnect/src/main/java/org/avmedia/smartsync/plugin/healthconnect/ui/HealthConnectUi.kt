package org.avmedia.smartsync.plugin.healthconnect.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import org.avmedia.smartsync.plugin.api.PluginUi
import org.avmedia.smartsync.plugin.healthconnect.HealthConnectPlugin
import org.avmedia.smartsync.plugin.healthconnect.HealthConnectSink
import org.avmedia.smartsync.plugin.healthconnect.HealthWriter
import org.avmedia.smartsync.plugin.healthconnect.RecordType
import org.avmedia.smartsync.plugin.healthconnect.SinkStatus

/** Settings row and screen: Health Connect status, the permission request, the last write. */
internal class HealthConnectUi(private val plugin: HealthConnectPlugin) : PluginUi {

    @Composable
    override fun SettingsEntry(onOpen: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp)) {
            Text(plugin.name, style = MaterialTheme.typography.titleMedium)
            Text(summary(), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        BackHandler(onBack = onBack)
        val host = plugin.host
        val sink = plugin.sink
        var refresh by remember { mutableIntStateOf(0) }
        var granted by remember { mutableStateOf<Set<RecordType>>(emptySet()) }
        val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
        LaunchedEffect(refresh, sink) { granted = runCatching { sink?.granted() }.getOrNull().orEmpty() }
        val context = LocalContext.current

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { TextButton(onClick = onBack) { Text("Back") } }
            Text("Health Connect", style = MaterialTheme.typography.headlineSmall)
            Text("Writes your watch's steps per hour, heart rate, resting heart rate, blood oxygen, sleep and workouts to Health Connect. Write only.")
            if (host == null || sink == null) {
                Text("Turn the plugin on first.")
                return@Column
            }
            val wanted = host.watches().flatMap { RecordType.offeredBy(it.capabilities) }.toSet()
            when {
                wanted.isEmpty() -> Text("None of your watches records health data.")
                sink.status() == SinkStatus.NOT_INSTALLED -> Text("Health Connect is not installed on this phone.")
                sink.status() == SinkStatus.UPDATE_REQUIRED -> {
                    Text("Health Connect needs an update.")
                    Button(onClick = {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Update Health Connect") }
                }
                else -> {
                    val missing = wanted - granted
                    Text(if (missing.isEmpty()) "Permission: allowed" else "Permission missing: ${missing.joinToString { HealthWriter.label(it) }}")
                    if (missing.isNotEmpty()) {
                        Button(onClick = { launcher.launch(HealthConnectSink.permissions(wanted)) }) { Text("Allow") }
                    }
                }
            }
            Text("Last write: ${lastWrite() ?: "never"}")
        }
    }

    private fun summary(): String = when {
        plugin.host == null -> "Off"
        else -> lastWrite()?.let { "Last write $it" } ?: "Nothing written yet"
    }

    private fun lastWrite(): String? {
        val store = plugin.host?.storage() ?: return null
        val at = store.get(HealthWriter.LAST_WRITE) ?: return null
        return "${at.replace('T', ' ')} (${store.get(HealthWriter.LAST_SUMMARY).orEmpty()})"
    }
}
