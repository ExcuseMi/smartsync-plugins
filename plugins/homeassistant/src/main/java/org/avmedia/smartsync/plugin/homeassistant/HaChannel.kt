package org.avmedia.smartsync.plugin.homeassistant

/** Home Assistant's websocket API as the plugin uses it (tests use a fake; the app uses [HaSocket]). */
interface HaChannel {
    fun start() {}

    fun stop() {}

    /** Authenticated and open now. */
    val ready: Boolean

    /** Sends [message] (an `id` is added) and waits for its result; a closed socket or a timeout is a failed reply. */
    suspend fun call(message: Map<String, Any?>): HaReply

    /** Sends [message] without waiting; false when not ready. */
    fun send(message: Map<String, Any?>): Boolean

    /** Sends a subscription; each event of it goes to [onEvent] until the socket closes. False when not ready. */
    fun subscribe(message: Map<String, Any?>, onEvent: (Map<String, Any?>) -> Unit): Boolean

    interface Listener {
        fun onReady()

        /** Closed or refused; [reason] holds no secret. [authRefused]: the token was not accepted. */
        fun onLost(reason: String, authRefused: Boolean = false)
    }
}

/** The answer to one [HaChannel.call]. [code] is Home Assistant's error code ("unauthorized", ...). */
data class HaReply(val success: Boolean, val result: Any? = null, val code: String? = null, val error: String? = null) {
    companion object {
        fun failed(code: String, error: String) = HaReply(false, code = code, error = error)
    }
}
