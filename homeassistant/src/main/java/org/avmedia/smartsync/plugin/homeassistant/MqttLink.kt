package org.avmedia.smartsync.plugin.homeassistant

/** The message a broker publishes for the plugin when its connection drops without a goodbye. */
data class Will(val topic: String, val payload: String, val retain: Boolean = true)

/** One connection to the broker. The plugin's logic sees only this (tests use a fake; the app uses [PahoLink]). */
interface MqttLink {
    val connected: Boolean

    /** Connects (and reconnects by itself after a drop or a failed first attempt) until [disconnect]. */
    fun connect(config: BrokerConfig, will: Will, listener: Listener)

    /** Queues one message; false when not connected or the client refused it (nothing is kept for later). */
    fun publish(topic: String, payload: String, retain: Boolean): Boolean

    fun subscribe(filter: String): Boolean

    /** Publishes [goodbye] when given and connected, then closes; no reconnect after this. */
    fun disconnect(goodbye: Will? = null)

    interface Listener {
        /** Connected (also after a reconnect): subscriptions are gone, everything is published again. */
        fun onConnected()

        /** The connection failed or dropped; [reason] holds no secret. */
        fun onConnectionLost(reason: String)

        fun onMessage(topic: String, payload: String)
    }
}
