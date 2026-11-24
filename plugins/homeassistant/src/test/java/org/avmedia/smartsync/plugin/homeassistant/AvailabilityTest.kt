package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every entity shows its last value while the app is online (2026-11-24: 38 sensors "unavailable" after a plugin
 * update): no expire_after, the states follow a changed config once Home Assistant set the entities up, and a value
 * not read yet keeps its retained last value.
 */
class AvailabilityTest {
    private val host = FakeHost()
    private val node = "gshock-aabbccddeeff"
    private val gbx = host.add(node, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG).also {
        host.values[it]!!["watch.battery"] = 80
        host.values[it]!!["health.hr_rest"] = 52
    }
    private val cfg = Watches.config().copy(mode = ConnectMode.THROUGH_HA, haUrl = "https://ha.example", haToken = "t")
    private val link = FakeLink(hasWill = false)
    private val delayed = mutableListOf<Pair<Long, () -> Unit>>()

    private fun bridge(version: String) = Bridge(host, link, cfg, version, later = { ms, b -> delayed += ms to b }) {}

    private fun runDelayed() {
        val now = delayed.toList()
        delayed.clear()
        now.forEach { it.second() }
    }

    private fun stateAfter(n: Int, obj: String) = link.published.drop(n).lastOrNull { it.topic == "smartsync/$node/state/$obj" }

    @Test fun anAppRestartWithANewPluginVersionSendsTheStatesAgainAfterTheConfigs() {
        bridge("0.1.0").onConnected()
        runDelayed()
        // The app restarts with a new plugin version: every config changes (origin sw_version), and Home Assistant
        // sets the entities up again, so the states are sent again once it had the time.
        val b = bridge("0.1.1")
        b.onConnected()
        assertTrue("states follow the changed configs", delayed.isNotEmpty() && delayed.all { it.first >= Bridge.ANNOUNCE_DELAY_MS })
        val n = link.published.size
        runDelayed()
        assertEquals("80", stateAfter(n, "battery")?.payload)
        assertEquals("52", stateAfter(n, "hr_rest")?.payload)
        assertTrue("no config in the announce", link.published.drop(n).none { it.topic.endsWith("/config") })
        // Unchanged configs (a heartbeat, a value update) send no announce.
        b.heartbeat()
        b.sync(gbx)
        assertTrue(delayed.isEmpty())
    }

    @Test fun homeAssistantComingOnlineGetsTheStatesAgainAfterItsEntitiesAreSetUp() = runTest {
        val b = bridge("0.1.1")
        b.onConnected()
        runDelayed()
        b.handle("homeassistant/status", "online")
        val n = link.published.size
        runDelayed()
        assertEquals("80", stateAfter(n, "battery")?.payload)
        assertEquals("online", link.published.drop(n).lastOrNull { it.topic == "smartsync/$node/availability" }?.payload)
    }

    @Test fun aValueTheWatchNeverGaveIsUnknownNotUnavailable() {
        bridge("0.1.1").onConnected()
        val configs = link.configs()
        assertTrue("no sensor expires", configs.values.none { it.contains("expire_after") })
        // No message at all for a value the watch never gave: Home Assistant shows it as unknown.
        assertTrue(link.published.none { it.topic == "smartsync/$node/state/temperature" })
        assertTrue(configs.containsKey("homeassistant/sensor/$node/temperature/config"))
    }

    @Test fun aValueNotReadYetAfterAnAppRestartKeepsItsRetainedLastValue() {
        bridge("0.1.1").onConnected()
        host.values[gbx]!!.remove("health.hr_rest")
        val n = link.published.size
        val b = bridge("0.1.1")
        b.onConnected()
        runDelayed()
        b.sync(gbx)
        assertTrue("never emptied", link.published.drop(n).none { it.topic == "smartsync/$node/state/hr_rest" })
        // The topic is still known, so it is emptied when the entity goes (health no longer shared).
        val off = Bridge(host, link, cfg.copy(shareHealth = false), "0.1.1") {}
        off.onConnected()
        assertEquals("", link.last("smartsync/$node/state/hr_rest")?.payload)
    }

    @Test fun workoutDistanceAndEnergyAreConfirmedTheirEntityIdsStay() {
        val byObj = Entities.VALUES.associateBy { it.obj }
        assertEquals("Last workout distance", byObj.getValue("workout_distance").name)
        assertEquals("Last workout energy", byObj.getValue("workout_kcal").name)
        assertEquals("last_workout_distance_unconfirmed_unit_likely_km", byObj.getValue("workout_distance").entityId)
        assertEquals("last_workout_energy_unconfirmed", byObj.getValue("workout_kcal").entityId)
    }
}
