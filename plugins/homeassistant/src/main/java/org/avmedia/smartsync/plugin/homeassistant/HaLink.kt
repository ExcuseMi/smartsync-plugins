package org.avmedia.smartsync.plugin.homeassistant

/**
 * [MqttLink] through Home Assistant: publishes with the `mqtt.publish` action and receives with `mqtt/subscribe`, so
 * the broker can stay inside the home network. There is no last will: the plugin publishes availability itself and
 * the plugin sends its goodbye on a planned stop ([Bridge.goingOffline]).
 */
class HaLink(private val channel: () -> HaChannel?) : MqttLink {
    private var listener: MqttLink.Listener? = null

    override val connected: Boolean get() = channel()?.ready == true
    override val hasWill: Boolean get() = false

    /** The socket itself is opened by the plugin; [ready] and [lost] are its events. */
    override fun connect(config: BrokerConfig, will: Will, listener: MqttLink.Listener) {
        this.listener = listener
        if (connected) listener.onConnected()
    }

    fun ready() = listener?.onConnected()

    fun lost(reason: String) = listener?.onConnectionLost(reason)

    override fun publish(topic: String, payload: String, retain: Boolean): Boolean =
        channel()?.send(publishMessage(topic, payload, retain)) == true

    override fun subscribe(filter: String): Boolean {
        val c = channel() ?: return false
        return c.subscribe(mapOf("type" to "mqtt/subscribe", "topic" to filter, "qos" to 1)) { e ->
            val t = e["topic"] as? String
            val p = e["payload"] as? String
            if (t != null && p != null) listener?.onMessage(t, p)
        }
    }

    override fun disconnect(goodbye: Will?) {
        if (goodbye != null) publish(goodbye.topic, goodbye.payload, goodbye.retain)
        listener = null
    }

    companion object {
        fun publishMessage(topic: String, payload: String, retain: Boolean): Map<String, Any?> = mapOf(
            "type" to "call_service", "domain" to "mqtt", "service" to "publish",
            "service_data" to mapOf("topic" to topic, "payload" to payload, "qos" to 1, "retain" to retain),
        )
    }
}
