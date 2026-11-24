package org.avmedia.smartsync.plugin.homeassistant

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.Delivered
import org.avmedia.smartsync.plugin.api.Delivery
import org.avmedia.smartsync.plugin.api.FailReason
import org.avmedia.smartsync.plugin.api.PluginResult
import org.avmedia.smartsync.plugin.api.WatchCommand
import org.avmedia.smartsync.plugin.api.WatchId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTest {
    private val host = FakeHost()
    private val link = FakeLink()
    private val logs = mutableListOf<String>()
    private val node = "gshock-aabbccddeeff"
    private val gbx: WatchId = host.add(node, "CASIO GBX-H5600", Watches.GBX_CAPS, Watches.GBX_CATALOG)
    private val bridge = Bridge(host, link, Watches.config(), "0.1.0") { logs += it }.also { it.onConnected() }

    private fun send(rest: String, payload: String, watchNode: String = node) = runTest { bridge.handle("smartsync/$watchNode/set/$rest", payload) }

    private fun results() = link.published.filter { it.topic == "smartsync/$node/result" }.map { it.payload }

    private fun settingsSent() = host.commands.map { (it.second as WatchCommand.ChangeSettings).values }

    @Test fun aSelectMessageBecomesAChangeSettingsCommand() {
        send("setting/watch_face", "Tide")
        assertEquals(1, host.commands.size)
        val (w, cmd, delivery) = host.commands.single()
        assertEquals(gbx, w)
        assertEquals(WatchCommand.ChangeSettings(mapOf("watch_face" to "Tide")), cmd)
        assertTrue(delivery is Delivery.NextConnection)
        assertEquals("""{"target":"setting/watch_face","value":"Tide","status":"done"}""", results().single())
        assertFalse("results are never retained", link.published.last().retain)
    }

    @Test fun eachSettingTypeGetsItsValue() {
        send("setting/auto_light", "OFF")
        send("setting/light_duration", "3")
        send("setting/step_goal", "12000")
        send("setting/bedtime", "22:30")
        assertEquals(
            listOf(mapOf("auto_light" to false), mapOf("light_duration" to 3), mapOf("step_goal" to 12000), mapOf("bedtime" to "22:30")),
            settingsSent(),
        )
    }

    @Test fun anInvalidValueIsRefusedAndNothingIsSent() {
        val bad = listOf(
            "watch_face" to "Nope", "watch_face" to "tide", "watch_face" to "", "auto_light" to "maybe", "light_duration" to "2",
            "step_goal" to "999", "step_goal" to "x", "bedtime" to "25:00", "bedtime" to "7:30",
        )
        bad.forEach { (k, v) -> send("setting/$k", v) }
        assertTrue("sent: ${host.commands}", host.commands.isEmpty())
        val r = results()
        assertEquals(bad.size, r.size)
        assertTrue(r.joinToString("\n"), r.all { it.contains("\"status\":\"refused\"") })
        assertTrue(r.first().contains("not one of: Activity, Heart rate, Tide"))
    }

    @Test fun anUnknownSettingCommandOrWatchIsRefused() {
        send("setting/no_such_setting", "1")
        send("reboot", "now")
        send("setting/watch_face", "Tide", watchNode = "gshock-000000000000")
        assertTrue(host.commands.isEmpty())
        assertEquals(2, results().size)
        assertTrue(results().all { it.contains("refused") })
    }

    @Test fun aNotificationGoesToTheWatchAndItsTextStaysOutOfLogsAndResults() {
        send("notify", "Door\nThe front door opened")
        val (w, n) = host.notifications.single()
        assertEquals(gbx, w)
        assertEquals("Door", n.title)
        assertEquals("The front door opened", n.text)
        assertFalse(results().single().contains("front door"))
        send("notify", "Only a line")
        assertEquals("Only a line", host.notifications.last().second.text)
        send("notify", "   ")
        assertEquals(2, host.notifications.size)
        assertTrue(logs.none { it.contains("front door") || it.contains("Only a line") })
    }

    @Test fun aJsonNotificationCarriesTitleMessageAppAndType() {
        send("notify", """{"title": "Calendar", "message": "Dentist at 10", "app": "Agenda", "type": "calendar"}""")
        val n = host.notifications.single().second
        assertEquals(org.avmedia.smartsync.plugin.api.NotificationKind.CALENDAR, n.kind)
        assertEquals("Agenda" to "Calendar", n.app to n.title)
        assertEquals("Dentist at 10", n.text)
        send("notify", """{"message": "x", "type": "nonsense"}""")
        assertEquals(org.avmedia.smartsync.plugin.api.NotificationKind.GENERIC, host.notifications.last().second.kind)
        assertEquals("Home Assistant", host.notifications.last().second.app)
        // What Home Assistant's notify entity sends (its command_template filled in).
        send("notify", """{"title": "", "message": "Washing machine done"}""")
        assertEquals("Washing machine done", host.notifications.last().second.text)
    }

    @Test fun aButtonPressWhileTheBrokerIsAwayIsSentAtTheNextConnection() {
        link.connected = false
        bridge.buttonPressed(gbx)
        assertTrue(link.published.none { it.topic.endsWith("/event/button") })
        link.connected = true
        bridge.onConnected()
        assertEquals(1, link.published.count { it.topic == "smartsync/$node/event/button" })
    }

    @Test fun aQueuedOrFailedWriteSaysSo() {
        host.answer = PluginResult.Ok(Delivered.AT_NEXT_CONNECTION)
        send("setting/watch_face", "Activity")
        assertTrue(results().last().contains("\"status\":\"queued\""))
        host.answer = PluginResult.Failed(FailReason.NOT_SUPPORTED, "no such setting on this model")
        send("setting/watch_face", "Activity")
        assertTrue(results().last().contains("\"status\":\"failed\""))
        assertTrue(results().last().contains("not_supported"))
    }

    @Test fun refreshAsksTheHostToReadValues() {
        send("refresh", "PRESS")
        assertEquals(WatchCommand.RefreshValues, host.commands.single().second)
    }

    @Test fun aButtonPressIsOneEventMessageNeverRetained() {
        bridge.buttonPressed(gbx)
        assertEquals(FakeLink.Msg("smartsync/$node/event/button", """{"event_type":"press"}""", false), link.published.last())
    }
}
