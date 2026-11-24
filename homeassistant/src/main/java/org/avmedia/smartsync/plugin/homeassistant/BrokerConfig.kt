package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.PluginStorage
import java.net.URI

/** How the plugin reaches the broker: plain MQTT (TCP, optionally TLS) or MQTT over websockets (ws, wss). */
enum class BrokerTransport(val scheme: String, val tls: Boolean, val websocket: Boolean, val defaultPort: Int) {
    TCP("tcp", false, false, 1883),
    TLS("ssl", true, false, 8883),
    WS("ws", false, true, 9001),
    WSS("wss", true, true, 443);

    companion object {
        fun of(tls: Boolean, websocket: Boolean): BrokerTransport = entries.first { it.tls == tls && it.websocket == websocket }

        /** The transport a URL scheme names ("mqtt" and "mqtts" too); null for an unknown one. */
        fun ofScheme(scheme: String): BrokerTransport? = when (scheme.lowercase()) {
            "tcp", "mqtt" -> TCP
            "ssl", "tls", "mqtts" -> TLS
            "ws" -> WS
            "wss" -> WSS
            else -> null
        }
    }
}

/**
 * The broker settings, kept in the plugin's storage. The password is a secret: [toString] masks it and [scrub] takes
 * it out of any text (an error message) before that text reaches a log line or the status.
 */
data class BrokerConfig(
    val host: String = "",
    val port: Int = BrokerTransport.TCP.defaultPort,
    val tls: Boolean = false,
    val websocket: Boolean = false,
    /** The websocket path ("/" or "/mqtt"); unused for TCP. */
    val path: String = "/",
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
    /** Publish Home Assistant MQTT discovery as well as the plain topics. */
    val discovery: Boolean = true,
    val discoveryPrefix: String = DEFAULT_DISCOVERY_PREFIX,
    val baseTopic: String = DEFAULT_BASE_TOPIC,
) {
    val transport: BrokerTransport get() = BrokerTransport.of(tls, websocket)

    /** What is wrong with these settings, null when they can be used. */
    fun problem(): String? = when {
        host.isBlank() -> "No broker address"
        host.any { it.isWhitespace() || it == '/' } -> "The broker address has a space or a slash"
        port !in 1..65535 -> "Port must be 1 to 65535"
        websocket && !path.startsWith("/") -> "The websocket path must start with /"
        !validTopicPart(baseTopic) -> "Base topic: letters, digits, _ - / only"
        discovery && !validTopicPart(discoveryPrefix) -> "Discovery prefix: letters, digits, _ - / only"
        password.isNotEmpty() && username.isBlank() -> "A password needs a user name"
        else -> null
    }

    val usable: Boolean get() = problem() == null

    /** The server URI Paho connects to: tcp://host:1883, ssl://host:8883, ws://host:9001/, wss://host/mqtt. */
    fun serverUri(): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val base = "${transport.scheme}://$h:$port"
        return if (websocket) base + path else base
    }

    /** [text] with the password masked. */
    fun scrub(text: String?): String {
        var t = text.orEmpty()
        if (password.isNotEmpty()) t = t.replace(password, "***")
        return t
    }

    override fun toString(): String =
        "BrokerConfig(${serverUri()}, user=${username.ifEmpty { "none" }}, password=${if (password.isEmpty()) "none" else "***"}, " +
            "clientId=$clientId, discovery=$discovery, discoveryPrefix=$discoveryPrefix, baseTopic=$baseTopic)"

    fun save(s: PluginStorage) {
        s.put(KEY_HOST, host.trim())
        s.put(KEY_PORT, port.toString())
        s.put(KEY_TLS, tls.toString())
        s.put(KEY_WS, websocket.toString())
        s.put(KEY_PATH, path.trim().ifEmpty { "/" })
        s.put(KEY_USER, username.trim())
        if (password.isEmpty()) s.delete(KEY_PASSWORD) else s.put(KEY_PASSWORD, password)
        s.put(KEY_CLIENT_ID, clientId.trim())
        s.put(KEY_DISCOVERY, discovery.toString())
        s.put(KEY_DISCOVERY_PREFIX, trimTopic(discoveryPrefix))
        s.put(KEY_BASE_TOPIC, trimTopic(baseTopic))
    }

    /**
     * The same settings with the broker taken from [input]: a bare host name, or a URL with a scheme (tcp, mqtt, ssl,
     * mqtts, ws, wss), an optional port and, for websockets, a path. A URL's user info is ignored (the password has
     * its own field, so it never sits in an address). Null when [input] is a URL that cannot be read.
     */
    fun withAddress(input: String): BrokerConfig? {
        val t = input.trim()
        if (!t.contains("://")) return copy(host = t)
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        val tr = BrokerTransport.ofScheme(uri.scheme ?: return null) ?: return null
        val h = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        val p = if (uri.port > 0) uri.port else tr.defaultPort
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return copy(host = h, port = p, tls = tr.tls, websocket = tr.websocket, path = if (tr.websocket) path else this.path)
    }

    companion object {
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        const val DEFAULT_BASE_TOPIC = "smartsync"

        const val KEY_HOST = "broker.host"
        const val KEY_PORT = "broker.port"
        const val KEY_TLS = "broker.tls"
        const val KEY_WS = "broker.websocket"
        const val KEY_PATH = "broker.path"
        const val KEY_USER = "broker.user"
        const val KEY_PASSWORD = "broker.password"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_DISCOVERY = "discovery"
        const val KEY_DISCOVERY_PREFIX = "discovery_prefix"
        const val KEY_BASE_TOPIC = "base_topic"

        fun load(s: PluginStorage): BrokerConfig {
            val tls = s.get(KEY_TLS) == "true"
            val ws = s.get(KEY_WS) == "true"
            return BrokerConfig(
                host = s.get(KEY_HOST).orEmpty(),
                port = s.get(KEY_PORT)?.toIntOrNull() ?: BrokerTransport.of(tls, ws).defaultPort,
                tls = tls,
                websocket = ws,
                path = s.get(KEY_PATH)?.takeIf { it.isNotBlank() } ?: "/",
                username = s.get(KEY_USER).orEmpty(),
                password = s.get(KEY_PASSWORD).orEmpty(),
                clientId = s.get(KEY_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: newClientId().also { s.put(KEY_CLIENT_ID, it) },
                discovery = s.get(KEY_DISCOVERY) != "false",
                discoveryPrefix = s.get(KEY_DISCOVERY_PREFIX)?.takeIf { it.isNotBlank() } ?: DEFAULT_DISCOVERY_PREFIX,
                baseTopic = s.get(KEY_BASE_TOPIC)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_TOPIC,
            )
        }

        fun newClientId(): String = "smartsync-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

        fun trimTopic(t: String): String = t.trim().trim('/')

        /** A topic prefix without wildcards, spaces or empty levels. */
        fun validTopicPart(t: String): Boolean {
            val x = trimTopic(t)
            return x.isNotEmpty() && x.length <= 100 && x.split('/').all { lvl -> lvl.isNotEmpty() && lvl.all { it.isLetterOrDigit() || it == '_' || it == '-' } }
        }
    }
}
