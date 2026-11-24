package org.avmedia.smartsync.plugin.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.avmedia.smartsync.plugin.api.PluginUi

/** The settings: Home Assistant address and token first, then how to connect (through Home Assistant or a broker), then topics. */
internal class HaUi(private val plugin: HomeAssistantPlugin) : PluginUi {
    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        val host = plugin.host() ?: return
        val saved = remember { BrokerConfig.load(host.storage()) }
        var mode by remember { mutableStateOf(saved.mode) }
        var haUrl by remember { mutableStateOf(saved.haUrl) }
        var haToken by remember { mutableStateOf(saved.haToken) }
        var address by remember { mutableStateOf(saved.host) }
        var port by remember { mutableStateOf(saved.port.toString()) }
        var tls by remember { mutableStateOf(saved.tls) }
        var websocket by remember { mutableStateOf(saved.websocket) }
        var path by remember { mutableStateOf(saved.path) }
        var user by remember { mutableStateOf(saved.username) }
        var password by remember { mutableStateOf(saved.password) }
        var clientId by remember { mutableStateOf(saved.clientId) }
        var discovery by remember { mutableStateOf(saved.discovery) }
        var prefix by remember { mutableStateOf(saved.discoveryPrefix) }
        var base by remember { mutableStateOf(saved.baseTopic) }
        var shareHealth by remember { mutableStateOf(saved.shareHealth) }
        var message by remember { mutableStateOf("") }
        val status by plugin.status.collectAsState()

        fun save() {
            val typed = saved.copy(
                mode = mode, haUrl = haUrl.trim(), haToken = haToken.trim(),
                host = address.trim(), port = port.trim().toIntOrNull() ?: -1, tls = tls, websocket = websocket,
                path = path.trim().ifEmpty { "/" }, username = user.trim(), password = password,
                clientId = clientId.trim().ifEmpty { BrokerConfig.newClientId() }, discovery = discovery,
                discoveryPrefix = prefix, baseTopic = base, shareHealth = shareHealth,
            )
            // A pasted URL (wss://broker.example/mqtt) fills transport, port and path.
            val cfg = if (mode == ConnectMode.DIRECT && address.contains("://")) typed.withAddress(address) else typed
            if (cfg == null) { message = "The address is not a URL the plugin can read"; return }
            val problem = cfg.problem()
            if (problem != null) { message = problem; return }
            cfg.save(host.storage())
            address = cfg.host; port = cfg.port.toString(); tls = cfg.tls; websocket = cfg.websocket; path = cfg.path
            clientId = cfg.clientId
            message = "Saved"
            plugin.restart()
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Home Assistant", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            // Saved at once (and the connection made again): off removes the health entities from Home Assistant.
            Toggle("Share health data with Home Assistant", shareHealth) { shareHealth = it; plugin.setShareHealth(it) }
            OutlinedTextField(
                haUrl, { haUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Home Assistant address") },
                supportingText = { Text("For example https://home.example.org") },
            )
            OutlinedTextField(
                haToken, { haToken = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Long-lived access token") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("An administrator's, from Profile > Security") },
            )
            Text("How to connect", style = MaterialTheme.typography.titleMedium)
            Choice("Through your Home Assistant (recommended)", mode == ConnectMode.THROUGH_HA) { mode = ConnectMode.THROUGH_HA }
            Choice("Directly to an MQTT broker", mode == ConnectMode.DIRECT) { mode = ConnectMode.DIRECT }
            Text(
                if (mode == ConnectMode.THROUGH_HA) "Needs the MQTT integration in Home Assistant." else "The address and token above are then for history only.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (mode == ConnectMode.DIRECT) {
                OutlinedTextField(
                    address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Broker address") }, supportingText = { Text("Host name or IP, or a URL: tcp://, ssl://, ws://, wss://") },
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Toggle("TLS (encrypted)", tls) { tls = it; port = BrokerTransport.of(tls, websocket).defaultPort.toString() }
                Toggle("Websockets", websocket) { websocket = it; port = BrokerTransport.of(tls, websocket).defaultPort.toString() }
                if (websocket) {
                    OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Websocket path") })
                }
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("User name") })
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client id") })
            }
            Toggle("Home Assistant discovery", discovery) { discovery = it }
            if (discovery) {
                OutlinedTextField(prefix, { prefix = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Discovery prefix") })
            }
            OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Base topic") })
            Button(onClick = { save() }) { Text("Save and connect") }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            Dashboards()
        }
    }

    /** Per watch: the dashboard the plugin keeps in Home Assistant (opt-in), and its YAML to copy. */
    @Composable
    private fun Dashboards() {
        val host = plugin.host() ?: return
        val watches = remember { host.watches() }
        if (watches.isEmpty()) return
        val results by plugin.dashboardStatus.collectAsState()
        val clipboard = LocalClipboardManager.current
        var yaml by remember { mutableStateOf<DashboardResult?>(null) }
        LaunchedEffect(yaml) { yaml?.takeIf { it.ok }?.let { clipboard.setText(AnnotatedString(it.yaml)) } }
        Text("Dashboards", style = MaterialTheme.typography.titleMedium)
        Text("Kept up to date by the plugin; without a token, copy the YAML instead.", style = MaterialTheme.typography.bodySmall)
        watches.forEach { w ->
            var on by remember(w.id) { mutableStateOf(plugin.dashboardWanted(w.id)) }
            Toggle("Create a dashboard for ${w.name}", on) { on = it; plugin.setDashboard(w.id, it) }
            TextButton(onClick = { plugin.dashboardYaml(w.id) { yaml = it } }) { Text("Copy the YAML of this dashboard") }
            results[w.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        yaml?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
