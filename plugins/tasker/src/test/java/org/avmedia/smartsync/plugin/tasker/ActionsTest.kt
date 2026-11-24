package org.avmedia.smartsync.plugin.tasker

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.FailReason
import org.avmedia.smartsync.plugin.api.NotificationKind
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.SettingInfo
import org.avmedia.smartsync.plugin.api.SettingType
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    private val host = FakeHost()
    private val a = host.addWatch("AA:01", "CASIO GBX-H5600", connected = true)
    private val b = host.addWatch("BB:02", "CASIO GW-B5600", connected = false)
    private val runner = ActionRunner(host)

    private val autoLight = SettingInfo("auto_light", SettingType.BOOL, label = "Auto light")
    private val stepGoal = SettingInfo("step_goal", SettingType.WHOLE, range = 1_000..50_000, label = "Step goal")
    private val face = SettingInfo("watch_face", SettingType.CHOICE, choices = listOf("Activity", "Simple"), label = "Watch face")
    private val alarm = SettingInfo("smart_alarm_time", SettingType.TIME, label = "Smart alarm time")

    init {
        host.catalogs[a] = listOf(autoLight, stepGoal, face, alarm)
        host.catalogs[b] = listOf(autoLight)
    }

    @Test fun aNotificationActionBecomesHostNotify() = runTest {
        val out = runner.run(ActionConfig(ActionKind.NOTIFY, WatchChoice.Connected, "Door", "Front door open", "Home", NotificationKind.MESSAGE))
        val (w, n, d) = host.notified.single()
        assertEquals(a, w)
        assertEquals(listOf(NotificationKind.MESSAGE, "Home", "Door", "Front door open"), listOf(n.kind, n.app, n.title, n.text))
        assertTrue("a watch that connects by its button gets it at its next connection", d is Delivery.NextConnection)
        assertTrue(out.ok)
        assertEquals(mapOf(Vars.RESULT to "ok", Vars.ERROR to ""), out.variables)
    }

    @Test fun aWriteKeptForTheNextConnectionIsQueued() = runTest {
        host.answer = PluginResult.Ok(Delivered.AT_NEXT_CONNECTION)
        val out = runner.run(ActionConfig(ActionKind.NOTIFY, WatchChoice.Specific(b, "CASIO GW-B5600"), "Hi"))
        assertEquals(b, host.notified.single().first)
        assertEquals("queued", out.variables[Vars.RESULT])
    }

    @Test fun aSettingActionBecomesChangeSettingsWithTheTypedValue() = runTest {
        runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "auto_light", value = "on"))
        runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "step_goal", value = " 12000 "))
        runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "watch_face", value = "simple"))
        runner.run(ActionConfig(ActionKind.ALARM, WatchChoice.Connected, setting = "smart_alarm_time", value = "6:30"))
        assertEquals(
            listOf(mapOf("auto_light" to true), mapOf("step_goal" to 12_000), mapOf("watch_face" to "Simple"), mapOf("smart_alarm_time" to "06:30")),
            host.commands.map { (it.second as WatchCommand.ChangeSettings).values },
        )
        assertTrue(host.commands.all { it.first == a })
    }

    @Test fun aSettingTheChosenWatchDoesNotHaveIsNotOfferedOrWritten() = runTest {
        assertEquals(listOf("auto_light"), SettingOffer.offered(host.settingCatalog(b), ActionKind.SETTING).map { it.key })
        assertTrue(SettingOffer.offered(host.settingCatalog(b), ActionKind.ALARM).isEmpty())
        val out = runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Specific(b, "b"), setting = "step_goal", value = "12000"))
        assertFalse(out.ok)
        assertEquals("failed", out.variables[Vars.RESULT])
        assertTrue(out.variables[Vars.ERROR]!!.startsWith("not_supported: step_goal"))
        assertTrue(host.commands.isEmpty())
    }

    @Test fun alarmsAndTimersAreTheirOwnAction() {
        assertEquals(listOf("smart_alarm_time"), SettingOffer.offered(host.settingCatalog(a), ActionKind.ALARM).map { it.key })
        assertFalse(SettingOffer.offered(host.settingCatalog(a), ActionKind.SETTING).any { it.key == "smart_alarm_time" })
    }

    @Test fun aBadValueIsRefusedBeforeAnythingIsWritten() = runTest {
        val out = runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "step_goal", value = "99"))
        assertEquals("invalid: step_goal: 1000 to 50000", out.variables[Vars.ERROR])
        assertEquals("invalid: watch_face: one of Activity, Simple",
            runner.run(ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "watch_face", value = "Fancy")).variables[Vars.ERROR])
        assertTrue(host.commands.isEmpty())
    }

    @Test fun aHostFailureComesBackAsVariables() = runTest {
        host.answer = PluginResult.Failed(FailReason.NOT_CONNECTED, "no link")
        val out = runner.run(ActionConfig(ActionKind.READ, WatchChoice.Connected))
        assertEquals(WatchCommand.RefreshValues, host.commands.single().second)
        assertEquals(mapOf(Vars.RESULT to "failed", Vars.ERROR to "not_connected: no link"), out.variables)
    }

    @Test fun anUnpairedOrMissingWatchFails() = runTest {
        val gone = runner.run(ActionConfig(ActionKind.NOTIFY, WatchChoice.Specific(WatchId("CC:03"), "Old watch"), "Hi"))
        assertEquals("invalid: Old watch is not paired", gone.variables[Vars.ERROR])
        host.paired.clear()
        assertEquals("invalid: no watch paired", runner.run(ActionConfig(ActionKind.READ, WatchChoice.Connected)).variables[Vars.ERROR])
        assertTrue(host.notified.isEmpty() && host.commands.isEmpty())
    }

    @Test fun connectedWatchFallsBackToTheOneConnectedLast() = runTest {
        host.paired.replaceAll { it.copy(connected = false) }
        runner.run(ActionConfig(ActionKind.NOTIFY, WatchChoice.Connected, "Hi"))
        assertEquals(a, host.notified.single().first)
    }

    @Test fun actionConfigsSurviveTheirBundleAndLetTaskerFillVariables() {
        val c = ActionConfig(ActionKind.NOTIFY, WatchChoice.Specific(a, "CASIO GBX-H5600"), "%title", "%text", "Home", NotificationKind.EMAIL)
        assertEquals(c, ActionConfig.fromMap(c.toMap()))
        assertEquals("gs.title gs.text gs.app", c.toMap()[Protocol.BUNDLE_VARIABLE_REPLACE_KEYS])
        val s = ActionConfig(ActionKind.SETTING, WatchChoice.Connected, setting = "step_goal", value = "%goal")
        assertEquals(s, ActionConfig.fromMap(s.toMap()))
        assertEquals("gs.value", s.toMap()[Protocol.BUNDLE_VARIABLE_REPLACE_KEYS])
    }
}
