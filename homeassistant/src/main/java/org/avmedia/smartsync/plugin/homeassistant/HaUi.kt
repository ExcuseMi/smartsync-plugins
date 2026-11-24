package org.avmedia.smartsync.plugin.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.avmedia.smartsync.plugin.api.PluginUi

/** The broker settings: address (host or URL), port, TLS, websockets, user, password, client id, topics. */
internal class HaUi(private val plugin: HomeAssistantPlugin) : PluginUi {
    @Composable
    override fun statusLine(): String {
        val s by plugin.status.collectAsState()
        return s
    }

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) {
        val host = plugin.host() ?: return
        val saved = remember { BrokerConfig.load(host.storage()) }
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
        var message by remember { mutableStateOf("") }
        val status by plugin.status.collectAsState()

        fun save() {
            val typed = saved.copy(
                host = address.trim(), port = port.trim().toIntOrNull() ?: -1, tls = tls, websocket = websocket,
                path = path.trim().ifEmpty { "/" }, username = user.trim(), password = password,
                clientId = clientId.trim().ifEmpty { BrokerConfig.newClientId() }, discovery = discovery,
                discoveryPrefix = prefix, baseTopic = base,
            )
            // A pasted URL (wss://broker.example/mqtt) fills transport, port and path.
            val cfg = if (address.contains("://")) typed.withAddress(address) else typed
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
            Text("MQTT", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Publishes each watch's values to your MQTT broker and takes commands back (settings, notifications). " +
                    "With discovery on, Home Assistant adds one device per watch by itself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
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
            Toggle("Home Assistant discovery", discovery) { discovery = it }
            if (discovery) {
                OutlinedTextField(prefix, { prefix = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Discovery prefix") })
            }
            OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Base topic") })
            Button(onClick = { save() }) { Text("Save and connect") }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
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
