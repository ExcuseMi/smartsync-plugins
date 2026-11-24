package org.avmedia.smartsync.plugin.homeassistant

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * [MqttLink] on the Eclipse Paho client: tcp, ssl, ws and wss (the server URI of [BrokerConfig.serverUri]). Paho
 * reconnects by itself after a drop; a failed first connection is retried here, 5 s doubling to 5 minutes.
 */
class PahoLink : MqttLink {
    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var stopped = false
    private var retry: ScheduledExecutorService? = null
    private var delaySec = FIRST_RETRY_SEC

    override val connected: Boolean get() = client?.isConnected == true

    override fun connect(config: BrokerConfig, will: Will, listener: MqttLink.Listener) {
        stopped = false
        retry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-plugin-retry").apply { isDaemon = true } }
        val c = MqttAsyncClient(config.serverUri(), config.clientId, MemoryPersistence())
        client = c
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 60
            connectionTimeout = 20
            maxInflight = 1000
            if (config.username.isNotBlank()) userName = config.username
            if (config.password.isNotEmpty()) password = config.password.toCharArray()
            setWill(will.topic, will.payload.toByteArray(), 1, will.retain)
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                delaySec = FIRST_RETRY_SEC
                listener.onConnected()
            }

            override fun connectionLost(cause: Throwable?) = listener.onConnectionLost(reason(config, cause))

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic != null && message != null) listener.onMessage(topic, String(message.payload, Charsets.UTF_8))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        fun attempt() {
            if (stopped) return
            try {
                c.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        listener.onConnectionLost(reason(config, exception))
                        schedule { attempt() }
                    }
                })
            } catch (e: Exception) {
                listener.onConnectionLost(reason(config, e))
                schedule { attempt() }
            }
        }
        attempt()
    }

    private fun schedule(block: () -> Unit) {
        if (stopped) return
        val d = delaySec
        delaySec = (delaySec * 2).coerceAtMost(MAX_RETRY_SEC)
        runCatching { retry?.schedule({ block() }, d, TimeUnit.SECONDS) }
    }

    override fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return try {
            c.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retain)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun subscribe(filter: String): Boolean {
        val c = client ?: return false
        return try {
            c.subscribe(filter, 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disconnect(goodbye: Will?) {
        stopped = true
        retry?.shutdownNow()
        retry = null
        val c = client ?: return
        client = null
        if (goodbye != null && c.isConnected) {
            runCatching { c.publish(goodbye.topic, goodbye.payload.toByteArray(), 1, goodbye.retain).waitForCompletion(2_000) }
        }
        runCatching { if (c.isConnected) c.disconnect(2_000).waitForCompletion(3_000) }
        runCatching { c.disconnectForcibly(0, 1_000) }
        runCatching { c.close() }
    }

    private companion object {
        const val FIRST_RETRY_SEC = 5L
        const val MAX_RETRY_SEC = 300L

        fun reason(config: BrokerConfig, e: Throwable?): String {
            val text = listOfNotNull(e?.message, e?.cause?.let { it.javaClass.simpleName + ": " + it.message }).joinToString(" / ")
            return config.scrub(text.ifBlank { e?.javaClass?.simpleName ?: "connection lost" })
        }
    }
}
