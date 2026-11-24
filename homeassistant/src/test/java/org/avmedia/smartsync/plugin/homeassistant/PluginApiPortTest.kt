package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.Control
import org.avmedia.smartsync.plugin.api.DataUse
import org.avmedia.smartsync.plugin.api.ValueType
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/** The plugin on plugin-api 0.1.0: the health switch, its needs, the periodic wake-up, time stamps and its details page. */
class PluginApiPortTest {
    private val host = FakeHost()
    private val node = "gshock-aabbccddeeff"
    private val gbx = host.add(node, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG).also {
        host.values[it]!!.putAll(mapOf("watch.battery" to 80, "health.steps_today" to 1234, "health.sleep_total_min" to 431))
        host.settingValues[it]!!["watch_face"] = "Tide"
    }

    @Test fun healthOffRemovesTheHealthEntitiesAndSendsNoHealthValue() {
        val link = FakeLink()
        Bridge(host, link, Watches.config(), "0.1.0") {}.onConnected()
        val p = "homeassistant/sensor/$node"
        assertTrue(link.configs().getValue("$p/steps_today/config").isNotEmpty())

        val off = FakeLink()
        // Same storage: the topics published with health on are known and emptied.
        Bridge(host, off, Watches.config().copy(shareHealth = false), "0.1.0") {}.onConnected()
        val c = off.configs()
        listOf("steps_today", "heart_rate", "sleep_total", "spo2", "workout_sport").forEach {
            assertEquals("$it still in Home Assistant", "", c["$p/$it/config"])
        }
        assertTrue("the battery stays", c.getValue("$p/battery/config").isNotEmpty())
        assertTrue("the controls stay", c.getValue("homeassistant/select/$node/setting_watch_face/config").isNotEmpty())
        val states = off.published.filter { it.topic.startsWith("smartsync/$node/state/") && it.payload.isNotEmpty() }.map { it.topic }
        assertFalse(states.toString(), states.any { it.endsWith("/steps_today") || it.endsWith("/sleep_total") })
        assertEquals("80", off.last("smartsync/$node/state/battery")?.payload)
    }

    @Test fun theSwitchIsOnUntilTurnedOffAndIsSaved() {
        val s = MemStorage()
        assertTrue(BrokerConfig.load(s).shareHealth)
        Watches.config().copy(shareHealth = false).save(s)
        assertFalse(BrokerConfig.load(s).shareHealth)
    }

    @Test fun needsFollowTheSwitchAndTheWayOfConnecting() {
        val on = HomeAssistantPlugin.needs(Watches.config())
        assertTrue(on.settings && on.buttonPress && on.healthReads)
        assertTrue("health.steps_today" in on.values!!)
        assertNull("direct without the Home Assistant API: no wake-up", on.wakeEveryMinutes)

        val off = HomeAssistantPlugin.needs(Watches.config().copy(shareHealth = false))
        assertFalse(off.healthReads)
        assertTrue(off.values!!.none { it.startsWith("health.") || it.startsWith("workout.") })
        assertTrue("watch.battery" in off.values!!)

        val through = Watches.config().copy(mode = ConnectMode.THROUGH_HA, haUrl = "https://ha.example", haToken = "t")
        assertEquals(HomeAssistantPlugin.HEARTBEAT_WAKE_MIN, HomeAssistantPlugin.needs(through).wakeEveryMinutes)
        assertTrue("woken before the sensors expire", HomeAssistantPlugin.HEARTBEAT_WAKE_MIN * 60 < Bridge.EXPIRE_AFTER_SEC)
        val directWithApi = Watches.config().copy(haUrl = "https://ha.example", haToken = "t")
        assertEquals(HomeAssistantPlugin.GAP_WAKE_MIN, HomeAssistantPlugin.needs(directWithApi).wakeEveryMinutes)
        assertNull(HomeAssistantPlugin.needs(directWithApi.copy(shareHealth = false)).wakeEveryMinutes)
    }

    @Test fun aWakeWithoutALastWillSendsTheStatesAgain() = runTest {
        Watches.config().save(host.plugin)
        val link = FakeLink(hasWill = false)
        val plugin = HomeAssistantPlugin({ link }, {})
        plugin.activate(host)
        assertEquals(HomeAssistantPlugin.needs(Watches.config()), host.needs)
        val n = link.published.size
        plugin.onEvent(WatchEvent.Wake)
        assertTrue(link.published.drop(n).any { it.topic == "smartsync/$node/state/battery" && it.payload == "80" })
        plugin.deactivate()
    }

    @Test fun aRePairedWatchLeavesHomeAssistantUnderItsOldIdAndShowsUnderItsNewOne() = runTest {
        Watches.config().save(host.plugin)
        val link = FakeLink()
        val plugin = HomeAssistantPlugin({ link }, {})
        plugin.activate(host)
        val old = link.published.filter { it.retain && it.topic.contains(node) && it.payload.isNotEmpty() }.map { it.topic }.toSet()
        assertTrue(old.size > 30)
        // The host moved the watch's data to its new address: only the new id is paired now.
        val newNode = "gshock-c51422f1e489"
        host.remove(gbx)
        val moved = host.add(newNode, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG)
        host.values[moved]!!["watch.battery"] = 80
        plugin.onEvent(WatchEvent.WatchRePaired(gbx, moved))
        old.forEach { assertEquals("$it emptied", "", link.last(it)?.payload) }
        assertEquals("80", link.last("smartsync/$newNode/state/battery")?.payload)
        assertFalse(host.plugin.keys().any { it == Bridge.TOPICS_KEY + node })
        plugin.deactivate()
    }

    @Test fun settingStatesComeFromTheHostsSettings() {
        val link = FakeLink()
        Bridge(host, link, Watches.config(), "0.1.0") {}.onConnected()
        assertEquals("Tide", link.last("smartsync/$node/state/setting/watch_face")?.payload)
    }

    @Test fun aLocalTimeGetsThePhonesOffsetAndATimeWithOffsetKeepsIt() {
        val e = Entities.VALUES.first { it.key == "health.recorded_at" }
        val zone = ZoneId.of("Europe/Brussels")
        val local = Entities.convert(e, "2026-11-24T07:12:00", zone, ValueType.LOCAL_DATE_TIME) as String
        assertEquals(OffsetDateTime.parse("2026-11-24T07:12:00+01:00"), OffsetDateTime.parse(local))
        val withOffset = Entities.convert(e, "2026-11-24T07:12:00Z", zone, ValueType.DATE_TIME) as String
        assertEquals(OffsetDateTime.parse("2026-11-24T07:12:00Z"), OffsetDateTime.parse(withOffset))
        val sync = Entities.VALUES.first { it.key == "app.last_sync" }
        assertEquals(Convert.TIMESTAMP, sync.convert)
        Entities.VALUES.filter { it.deviceClass == "timestamp" }.forEach { assertEquals(it.key, Convert.TIMESTAMP, it.convert) }
    }

    @Test fun theDetailsPageHasItsMetadataAndAccessIsEverythingButLocation() {
        val p = HomeAssistantPlugin()
        listOf(p.summary, p.description, p.setupNeeds, p.author, p.license).forEach { assertTrue(it.isNotBlank()) }
        val sentences = p.description.split(". ").size
        assertTrue("2 to 3 sentences: ${p.description}", sentences in 2..3)
        assertTrue(p.infoUrl!!.endsWith("/docs/homeassistant.md"))
        listOf(p.infoUrl, p.sourceUrl, p.issuesUrl).forEach { assertTrue(it!!.startsWith("https://github.com/ExcuseMi/smartsync-plugins")) }
        assertTrue(java.io.File("../docs/homeassistant.md").isFile)
        assertEquals(DataUse.ALL - DataUse.PHONE_LOCATION, p.access.reads)
        assertEquals(Control.ALL, p.access.controls)
    }
}
