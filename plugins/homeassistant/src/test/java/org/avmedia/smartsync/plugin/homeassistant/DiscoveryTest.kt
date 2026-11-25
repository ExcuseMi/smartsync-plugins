package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val host = FakeHost()
    private val link = FakeLink()
    private val gbxNode = "gshock-aabbccddeeff"
    private val ocwNode = "gshock-112233445566"

    private fun bridge(cfg: BrokerConfig = Watches.config(), l: FakeLink = link) = Bridge(host, l, cfg, "0.1.0") {}

    private fun addGbx() = host.add(gbxNode, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG).also {
        host.values[it]!!.putAll(mapOf("watch.battery" to 80, "health.steps_today" to 1234, "watch.firmware" to "1.02", "health.sleep_total_min" to 431))
        host.settingValues[it]!!.putAll(mapOf("watch_face" to "Tide", "auto_light" to true))
    }

    private fun addOcw() = host.add(ocwNode, "CASIO OCW-S7000", Watches.OCW_CAPS, Watches.OCW_CATALOG).also {
        host.values[it]!!["watch.battery"] = 55
    }

    @Test fun aGbxGetsHealthSensorsAndControlsFromItsCatalogue() {
        addGbx()
        bridge().onConnected()
        val c = link.configs()
        val p = "homeassistant"
        listOf("steps_today", "heart_rate", "sleep_total", "spo2", "workout_sport", "battery", "temperature", "firmware").forEach {
            assertTrue("missing $it", c.containsKey("$p/sensor/$gbxNode/$it/config"))
        }
        val select = c.getValue("$p/select/$gbxNode/setting_watch_face/config")
        assertTrue(select, select.contains("\"options\":[\"Activity\",\"Heart rate\",\"Tide\"]"))
        assertTrue(select.contains("\"command_topic\":\"smartsync/$gbxNode/set/setting/watch_face\""))
        assertTrue(select.contains("\"state_topic\":\"smartsync/$gbxNode/state/setting/watch_face\""))
        assertTrue(c.containsKey("$p/switch/$gbxNode/setting_auto_light/config"))
        assertTrue(c.getValue("$p/select/$gbxNode/setting_light_duration/config").contains("\"options\":[\"1\",\"3\"]"))
        val number = c.getValue("$p/number/$gbxNode/setting_step_goal/config")
        assertTrue(number, number.contains("\"min\":1000") && number.contains("\"max\":50000"))
        assertTrue(c.getValue("$p/text/$gbxNode/setting_bedtime/config").contains("\"pattern\""))
        assertTrue(c.containsKey("$p/notify/$gbxNode/notify/config"))
        assertTrue(c.getValue("$p/event/$gbxNode/button/config").contains("\"event_types\":[\"press\"]"))
        val trigger = c.getValue("$p/device_automation/$gbxNode/button_press/config")
        assertTrue(trigger, trigger.contains("\"automation_type\":\"trigger\"") && trigger.contains("\"topic\":\"smartsync/$gbxNode/event/button\""))
        assertTrue(c.getValue("$p/notify/$gbxNode/notify/config").contains("command_template"))
        val battery = c.getValue("$p/sensor/$gbxNode/battery/config")
        assertTrue(battery, battery.contains("\"model\":\"GBX-H5600\"") && battery.contains("\"sw_version\":\"1.02\""))
        assertTrue(battery.contains("\"identifiers\":[\"smartsync_$gbxNode\"]"))
        assertTrue(battery.contains("smartsync/bridge/availability") && battery.contains("smartsync/$gbxNode/availability"))

        assertEquals(FakeLink.Msg("smartsync/$gbxNode/state/battery", "80", true), link.last("smartsync/$gbxNode/state/battery"))
        assertEquals("Tide", link.last("smartsync/$gbxNode/state/setting/watch_face")?.payload)
        assertEquals("ON", link.last("smartsync/$gbxNode/state/setting/auto_light")?.payload)
        assertEquals("ON", link.last("smartsync/$gbxNode/state/connected")?.payload)
        assertEquals("online", link.last("smartsync/$gbxNode/availability")?.payload)
        assertEquals("online", link.last("smartsync/bridge/availability")?.payload)
        assertTrue(link.subscriptions.contains("smartsync/+/set/#"))
        assertTrue("every retained", link.published.all { it.retain })
    }

    @Test fun anOcwGetsOnlyWhatItHasAndNoTideNextToAGbx() {
        addGbx()
        addOcw()
        bridge().onConnected()
        val c = link.configs()
        val ocw = c.filterKeys { it.contains("/$ocwNode/") }
        listOf("battery", "temperature", "home_city", "connected", "last_sync").forEach { o ->
            assertTrue("missing $o", ocw.keys.any { it.endsWith("/$o/config") })
        }
        listOf("steps_today", "heart_rate", "sleep_total", "spo2", "workout_sport", "firmware", "timer").forEach { o ->
            assertFalse("OCW has $o", ocw.keys.any { it.endsWith("/$o/config") })
        }
        assertFalse("OCW has no notifications", ocw.keys.any { it.contains("/notify/") })
        assertFalse("OCW has no select", ocw.keys.any { it.contains("/select/") })
        assertFalse("OCW has no tide", ocw.values.any { it.contains("Tide") })
        assertTrue(ocw.containsKey("homeassistant/switch/$ocwNode/setting_button_tone/config"))
        assertTrue("the GBX keeps its tide", c.getValue("homeassistant/select/$gbxNode/setting_watch_face/config").contains("Tide"))
        assertFalse(link.published.any { it.topic.startsWith("smartsync/$ocwNode/state/steps") })
    }

    @Test fun unchangedValuesAreNotPublishedAgain() {
        val gbx = addGbx()
        val b = bridge()
        b.onConnected()
        val count = link.published.size
        b.sync(gbx)
        b.syncAll()
        assertEquals(count, link.published.size)
        host.values[gbx]!!["watch.battery"] = 79
        b.sync(gbx)
        assertEquals(count + 1, link.published.size)
        assertEquals("79", link.published.last().payload)
    }

    @Test fun aCatalogueOrCapabilityChangeRepublishesAndEmptiesWhatIsGone() {
        val gbx = addGbx()
        val b = bridge()
        b.onConnected()
        host.catalogs[gbx] = listOf(SettingInfo("watch_face", SettingType.CHOICE, choices = listOf("Activity", "Tide")))
        host.setCaps(gbx, Watches.GBX_CAPS - org.avmedia.smartsync.plugin.api.Capability.SLEEP)
        b.sync(gbx)
        assertTrue(link.last("homeassistant/select/$gbxNode/setting_watch_face/config")!!.payload.contains("\"options\":[\"Activity\",\"Tide\"]"))
        listOf(
            "homeassistant/switch/$gbxNode/setting_auto_light/config", "smartsync/$gbxNode/state/setting/auto_light",
            "homeassistant/sensor/$gbxNode/sleep_total/config", "smartsync/$gbxNode/state/sleep_total",
        ).forEach { t ->
            assertEquals(t, FakeLink.Msg(t, "", true), link.last(t))
        }
    }

    @Test fun aForgottenWatchLeavesNoRetainedTopics() {
        val gbx = addGbx()
        val b = bridge()
        b.onConnected()
        val retained = link.published.filter { it.retain && it.topic.contains(gbxNode) }.map { it.topic }.toSet()
        assertTrue(retained.size > 30)
        host.remove(gbx)
        b.removeWatch(gbx)
        retained.forEach { assertEquals(it, "", link.last(it)?.payload) }
        assertFalse(host.plugin.keys().any { it.startsWith(Bridge.TOPICS_KEY) })
    }

    @Test fun aWatchForgottenWhileThePluginWasOffIsEmptiedAtTheNextConnection() {
        val gbx = addGbx()
        bridge().onConnected()
        host.remove(gbx)
        val next = FakeLink()
        bridge(l = next).onConnected()
        assertEquals("", next.last("homeassistant/sensor/$gbxNode/battery/config")?.payload)
        assertEquals("", next.last("smartsync/$gbxNode/state/battery")?.payload)
    }

    @Test fun withDiscoveryOffOnlyThePlainTopicsArePublished() {
        addGbx()
        bridge(Watches.config().copy(discovery = false)).onConnected()
        assertTrue(link.configs().isEmpty())
        assertEquals("80", link.last("smartsync/$gbxNode/state/battery")?.payload)
    }

    @Test fun nothingIsMarkedSentWhileTheBrokerIsAway() {
        val gbx = addGbx()
        val b = bridge()
        link.connected = false
        b.sync(gbx)
        assertTrue(link.published.isEmpty())
        link.connected = true
        b.onConnected()
        assertEquals("80", link.last("smartsync/$gbxNode/state/battery")?.payload)
    }

    private fun floors(supported: Boolean?) = listOf(
        org.avmedia.smartsync.plugin.api.MeasurementInfo("floors", "Floors", "floors", "day_total", listOf("health.stairs_up", "health.stairs_down"), supported),
        org.avmedia.smartsync.plugin.api.MeasurementInfo("steps", "Steps", "steps", "day_total", listOf("health.steps_today"), true),
    )

    @Test fun aMeasurementTheFamilyDoesNotHaveGetsNoEntityAndAnOldOneIsRemoved() {
        val w = addGbx()
        val b = bridge()
        b.onConnected()
        val p = "homeassistant/sensor/$gbxNode"
        val stepsBefore = link.configs().getValue("$p/steps_today/config")
        assertTrue(link.configs().getValue("$p/stairs_up/config").isNotEmpty())
        host.measurements[w] = floors(false)
        b.onConnected()
        assertEquals("floors removed from discovery", "", link.last("$p/stairs_up/config")?.payload)
        assertEquals("", link.last("$p/stairs_down/config")?.payload)
        assertEquals("a kept entity stays the same, id included", stepsBefore, link.configs().getValue("$p/steps_today/config"))
        assertTrue(stepsBefore.contains("\"default_entity_id\":\"sensor.casio_gbx_h5600_steps_today\""))
    }

    @Test fun unknownSupportKeepsTheEntity() {
        val w = addGbx()
        host.measurements[w] = floors(null)
        bridge().onConnected()
        assertTrue(link.configs().getValue("homeassistant/sensor/$gbxNode/stairs_up/config").isNotEmpty())
    }
}
