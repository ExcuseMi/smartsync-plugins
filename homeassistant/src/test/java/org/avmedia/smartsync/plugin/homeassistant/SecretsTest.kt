package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.PluginStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The broker password never reaches a log line, the status or another storage key; addresses parse to the right URI. */
class SecretsTest {
    private val pw = Watches.CONFIG_PASSWORD

    @Test fun thePasswordIsNotInToStringOrAScrubbedError() {
        val cfg = Watches.config()
        assertFalse(cfg.toString().contains(pw))
        assertTrue(cfg.toString().contains("password=***"))
        assertFalse(cfg.scrub("not authorised: user smartsync password $pw").contains(pw))
    }

    @Test fun aConnectionErrorWithThePasswordReachesNeitherLogNorStatus() = runTest {
        val host = FakeHost()
        Watches.config().save(host.plugin)
        val link = FakeLink(connected = false)
        val logs = mutableListOf<String>()
        val plugin = HomeAssistantPlugin({ link }, { logs += it })
        plugin.activate(host)
        assertEquals("smartsync-test", link.config?.clientId)
        link.listener!!.onConnectionLost("bad user name or password $pw")
        assertTrue(logs.joinToString(), logs.none { it.contains(pw) })
        assertTrue(host.statuses.any { it is PluginStatus.Error })
        assertTrue(host.statuses.none { it.text.contains(pw) })
        assertFalse(plugin.status.value.contains(pw))
        plugin.deactivate()
        assertEquals("smartsync/bridge/availability", link.goodbye?.topic)
    }

    @Test fun storageHoldsThePasswordOnlyUnderItsKey() {
        val s = MemStorage()
        Watches.config().save(s)
        assertEquals(setOf(BrokerConfig.KEY_PASSWORD), s.map.filterValues { it.contains(pw) }.keys)
        assertEquals(Watches.config(), BrokerConfig.load(s))
    }

    @Test fun eachTransportHasItsServerUri() {
        val c = BrokerConfig(host = "broker.lan", clientId = "x")
        assertEquals("tcp://broker.lan:1883", c.serverUri())
        assertEquals("ssl://broker.lan:8883", c.copy(tls = true, port = 8883).serverUri())
        assertEquals("ws://broker.lan:9001/", c.copy(websocket = true, port = 9001).serverUri())
        assertEquals("wss://mqtt.example.com:443/mqtt", c.copy(host = "mqtt.example.com", tls = true, websocket = true, port = 443, path = "/mqtt").serverUri())
    }

    @Test fun aPastedUrlFillsTransportPortAndPath() {
        val c = BrokerConfig(clientId = "x")
        val wss = c.withAddress("wss://mqtt.example.com/mqtt")!!
        assertEquals(listOf("mqtt.example.com", "443", "true", "true", "/mqtt"), listOf(wss.host, "${wss.port}", "${wss.tls}", "${wss.websocket}", wss.path))
        val ws = c.withAddress("ws://10.0.0.5:9001")!!
        assertEquals("ws://10.0.0.5:9001/", ws.serverUri())
        assertEquals("ssl://h.example:8883", c.withAddress("mqtts://h.example")!!.serverUri())
        assertEquals("tcp://h.example:1884", c.withAddress("mqtt://user:pw@h.example:1884")!!.serverUri())
        assertNull(c.withAddress("ftp://h.example"))
        assertEquals("broker.lan", c.withAddress("broker.lan")!!.host)
    }

    @Test fun unusableSettingsAreRefused() {
        val ok = Watches.config()
        assertNull(ok.problem())
        listOf(
            ok.copy(host = ""), ok.copy(host = "a b"), ok.copy(port = 0), ok.copy(port = 70000), ok.copy(baseTopic = "a/+"),
            ok.copy(discoveryPrefix = "#"), ok.copy(username = ""), ok.copy(websocket = true, path = "mqtt"),
        ).forEach { assertNotNull(it.toString(), it.problem()) }
    }
}
