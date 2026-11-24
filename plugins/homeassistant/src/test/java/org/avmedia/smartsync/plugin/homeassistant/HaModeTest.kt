package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.FailReason
import org.avmedia.smartsync.plugin.api.PluginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Through Home Assistant (no last will), Home Assistant restarts, and controls that show the watch's real value. */
class HaModeTest {
    private val host = FakeHost()
    private val gbxNode = "gshock-aabbccddeeff"
    private val ocwNode = "gshock-112233445566"
    private val gbx = host.add(gbxNode, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG).also {
        host.values[it]!!["watch.battery"] = 80
        host.settingValues[it]!!["watch_face"] = "Tide"
    }
    private val ocw = host.add(ocwNode, "CASIO OCW-S7000", Watches.OCW_CAPS, Watches.OCW_CATALOG)
    private val cfg = Watches.config().copy(mode = ConnectMode.THROUGH_HA, haUrl = "https://ha.example", haToken = "eyJ0-token-value")
    private val link = FakeLink(hasWill = false)
    private val bridge = Bridge(host, link, cfg, "0.1.0") {}

    private fun after(n: Int) = link.published.drop(n)

    @Test fun throughHomeAssistantTheHeartbeatSendsTheStatesAgain() {
        bridge.onConnected()
        val n = link.published.size
        bridge.heartbeat()
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/battery" && it.payload == "80" })
        assertTrue(after(n).any { it.topic == "smartsync/bridge/availability" && it.payload == "online" })
        assertTrue("configs are not sent by the heartbeat", after(n).none { it.topic.endsWith("/config") })
    }

    @Test fun anAlwaysConnectedWatchGoesOfflineWhenItDisconnectsAButtonWatchDoesNot() {
        bridge.onConnected()
        bridge.connectionChanged(gbx, false)
        assertEquals("offline", link.last("smartsync/$gbxNode/availability")?.payload)
        assertEquals("OFF", link.last("smartsync/$gbxNode/state/connected")?.payload)
        bridge.connectionChanged(ocw, false)
        assertEquals("online", link.last("smartsync/$ocwNode/availability")?.payload)
        bridge.connectionChanged(gbx, true)
        assertEquals("online", link.last("smartsync/$gbxNode/availability")?.payload)
    }

    @Test fun aPlannedStopPublishesOfflineForTheBridgeAndEveryWatch() {
        bridge.onConnected()
        bridge.goingOffline()
        listOf("smartsync/bridge/availability", "smartsync/$gbxNode/availability", "smartsync/$ocwNode/availability").forEach {
            assertEquals(it, FakeLink.Msg(it, "offline", true), link.last(it))
        }
    }

    @Test fun homeAssistantComingOnlineGetsEveryConfigAndStateAgain() = runTest {
        bridge.onConnected()
        assertTrue(link.subscriptions.contains("homeassistant/status"))
        val configs = link.configs().size
        val n = link.published.size
        bridge.handle("homeassistant/status", "offline")
        assertEquals(n, link.published.size)
        bridge.handle("homeassistant/status", "online")
        assertEquals(configs, after(n).count { it.topic.endsWith("/config") })
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/battery" && it.payload == "80" })
    }

    @Test fun afterAReconnectTheStatesShowTheWatchsRealValues() {
        bridge.onConnected()
        val n = link.published.size
        // A change made in Home Assistant while the app was away never reached the watch: the state says what the watch has.
        link.connected = false
        link.connected = true
        bridge.onConnected()
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/setting/watch_face" && it.payload == "Tide" && it.retain })
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/battery" && it.payload == "80" })
    }

    @Test fun aRefusedOrFailedChangeShowsTheRealValueAgain() = runTest {
        bridge.onConnected()
        var n = link.published.size
        bridge.handle("smartsync/$gbxNode/set/setting/watch_face", "Nope")
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/setting/watch_face" && it.payload == "Tide" })
        host.answer = PluginResult.Failed(FailReason.NOT_CONNECTED, "watch away")
        n = link.published.size
        bridge.handle("smartsync/$gbxNode/set/setting/watch_face", "Activity")
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/result" && it.payload.contains("not_connected") })
        assertTrue(after(n).any { it.topic == "smartsync/$gbxNode/state/setting/watch_face" && it.payload == "Tide" })
    }

    @Test fun theHaLinkPublishesWithTheMqttActionAndReceivesThroughSubscribe() {
        val ch = FakeChannel()
        val l = HaLink { ch }
        val got = mutableListOf<Pair<String, String>>()
        var connected = 0
        l.connect(cfg, Will("t", "offline"), object : MqttLink.Listener {
            override fun onConnected() { connected++ }
            override fun onConnectionLost(reason: String) {}
            override fun onMessage(topic: String, payload: String) { got += topic to payload }
        })
        assertEquals(1, connected)
        assertFalse(l.hasWill)
        assertTrue(l.publish("smartsync/x/state/battery", "80", true))
        assertEquals(
            mapOf(
                "type" to "call_service", "domain" to "mqtt", "service" to "publish",
                "service_data" to mapOf("topic" to "smartsync/x/state/battery", "payload" to "80", "qos" to 1, "retain" to true),
            ),
            ch.sent.last(),
        )
        assertTrue(l.subscribe("smartsync/+/set/#"))
        assertEquals(mapOf("type" to "mqtt/subscribe", "topic" to "smartsync/+/set/#", "qos" to 1), ch.subs.last().first)
        ch.subs.last().second(mapOf("topic" to "smartsync/x/set/notify", "payload" to "hi", "qos" to 0))
        assertEquals(listOf("smartsync/x/set/notify" to "hi"), got)
        ch.isReady = false
        assertFalse(l.connected)
    }

    @Test fun throughHomeAssistantNeedsItsAddressAndAnAdminTokenNeverShown() {
        assertNull(cfg.problem())
        assertEquals("wss://ha.example/api/websocket", cfg.haWebsocketUrl())
        assertEquals("ws://192.168.1.5:8123/api/websocket", cfg.copy(haUrl = "http://192.168.1.5:8123/").haWebsocketUrl())
        assertNotNull(cfg.copy(haUrl = "").problem())
        assertNotNull(cfg.copy(haToken = "").problem())
        assertNotNull(cfg.copy(haUrl = "ha.example").problem())
        // Direct mode: the broker is required, Home Assistant only for history (both or none).
        assertNull(Watches.config().problem())
        assertNotNull(Watches.config().copy(haToken = "x").problem())
        assertFalse(cfg.toString().contains("eyJ0-token-value"))
        assertFalse(cfg.scrub("auth with eyJ0-token-value refused").contains("eyJ0-token-value"))
        val s = MemStorage()
        cfg.save(s)
        assertEquals(setOf(BrokerConfig.KEY_HA_TOKEN), s.map.filterValues { it.contains("eyJ0-token-value") }.keys)
        assertEquals(cfg, BrokerConfig.load(s))
    }
}
