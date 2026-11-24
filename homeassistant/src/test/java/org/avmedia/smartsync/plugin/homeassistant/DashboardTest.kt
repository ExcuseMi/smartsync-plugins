package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.PairedWatch
import org.avmedia.smartsync.plugin.api.WatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's own dashboard: made from the entities the watch has, kept up to date, never one it did not make. */
class DashboardTest {
    private val id = WatchId("gshock-aabbccddeeff")
    private val gbx = PairedWatch(id, "CASIO GBX-H5600", Watches.GBX_CAPS, true)
    private val path = "smartsync-aabbccddeeff"
    private val ch = FakeChannel()
    private val sync = DashboardSync { ch }

    private fun register(vararg objs: String) = objs.forEach { ch.entities["smartsync_${id.key}_$it"] = "sensor.casio_gbx_h5600_$it" }

    @Suppress("UNCHECKED_CAST")
    private fun saved(): Map<String, Any?> = ch.dashboards.getValue(path).second as Map<String, Any?>

    @Test fun aNewDashboardHasOnlyWhatTheWatchHas() = runTest {
        register("steps_today", "heart_rate", "battery", "connected", "setting_watch_face", "workout_sport")
        val r = sync.sync(gbx, 9000)
        assertTrue(r.message, r.ok)
        assertEquals(1, ch.calls("lovelace/dashboards/create").size)
        val text = Json.write(saved())
        assertTrue(Dashboard.isOurs(saved(), id))
        assertTrue(text, text.contains("\"max\":9000") && text.contains("sensor.casio_gbx_h5600_setting_watch_face"))
        assertTrue(text.contains("sensor.casio_gbx_h5600_workout_sport"))
        assertFalse("no sleep card without sleep entities", text.contains("Last night"))
    }

    @Test fun aUserMadeDashboardAtTheSamePathIsLeftAlone() = runTest {
        register("steps_today")
        val mine = mapOf("title" to "Mine", "views" to listOf(mapOf("cards" to emptyList<Any>())))
        ch.dashboards[path] = "id_user" to mine
        assertFalse(sync.sync(gbx, null).ok)
        assertEquals(mine, ch.dashboards.getValue(path).second)
        assertTrue(ch.calls("lovelace/config/save").isEmpty())
        sync.remove(id)
        assertTrue("never deleted", ch.dashboards.containsKey(path) && ch.calls("lovelace/dashboards/delete").isEmpty())
        // An empty dashboard someone made (no config saved yet) is not the plugin's either.
        ch.dashboards[path] = "id_user" to null
        assertFalse(sync.sync(gbx, null).ok)
        assertTrue(ch.calls("lovelace/config/save").isEmpty())
    }

    @Test fun anOutdatedOwnDashboardIsUpdatedAndACurrentOneIsNot() = runTest {
        register("steps_today")
        sync.sync(gbx, null)
        assertEquals(1, ch.calls("lovelace/config/save").size)
        assertEquals("Dashboard up to date", sync.sync(gbx, null).message)
        assertEquals(1, ch.calls("lovelace/config/save").size)
        register("sleep_score", "setting_bedtime")
        assertEquals("Dashboard updated", sync.sync(gbx, null).message)
        assertEquals(2, ch.calls("lovelace/config/save").size)
        assertTrue(Json.write(saved()).contains("sensor.casio_gbx_h5600_sleep_score"))
        assertEquals(1, ch.calls("lovelace/dashboards/create").size)
    }

    @Test fun itsOwnDashboardIsRemovedWhenAsked() = runTest {
        register("steps_today")
        sync.sync(gbx, null)
        assertEquals("Dashboard removed", sync.remove(id).message)
        assertFalse(ch.dashboards.containsKey(path))
    }

    @Test fun withoutAnAdministratorTheYamlStillComes() = runTest {
        register("steps_today")
        ch.refuse = "unauthorized"
        assertEquals(DashboardSync.NO_ADMIN, sync.sync(gbx, null).message)
        ch.states += listOf("sensor.casio_gbx_h5600_steps_today", "sensor.casio_gbx_h5600_recent_heart_rate", "select.casio_gbx_h5600_watch_face")
        val y = sync.yaml(gbx, Watches.GBX_CATALOG, 10000)
        assertTrue(y.message, y.ok)
        assertTrue(y.yaml, y.yaml.contains("entity: sensor.casio_gbx_h5600_steps_today") && y.yaml.contains("max: 10000"))
        assertTrue(y.yaml.contains("- select.casio_gbx_h5600_watch_face") && y.yaml.contains("sensor.casio_gbx_h5600_recent_heart_rate"))
        assertTrue(ch.dashboards.isEmpty())
    }

    @Test fun noHomeAssistantNoDashboard() = runTest {
        ch.isReady = false
        assertEquals(DashboardSync.NOT_CONNECTED, sync.sync(gbx, null).message)
    }
}
