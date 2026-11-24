package org.avmedia.smartsync.plugin.tasker

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.ConnectionKind
import org.avmedia.smartsync.plugin.api.Control
import org.avmedia.smartsync.plugin.api.PluginHost
import org.avmedia.smartsync.plugin.api.ValueCause
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.avmedia.smartsync.plugin.api.WatchValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PluginTest {
    private class Recorded : Signals {
        var host: PluginHost? = null
        val events = mutableListOf<Fired>()
        var states = 0
        override fun started(host: PluginHost) { this.host = host }
        override fun stopped() { host = null }
        override fun event(fired: Fired) { events += fired }
        override fun stateChanged() { states++ }
    }

    private val host = FakeHost()
    private val a = host.addWatch("AA:01", "CASIO GBX-H5600")
    private val out = Recorded()
    private val plugin = TaskerPlugin(out)

    @Test fun activateAsksForButtonPressesAndTheValuesItPasses() = runTest {
        plugin.activate(host)
        assertTrue(host.needs!!.buttonPress)
        assertEquals(ValueKeys.ALL, host.needs!!.values)
        assertTrue(Control.BUTTON_PRESS in plugin.access.controls)
        assertEquals(host, out.host)
        plugin.deactivate()
        assertEquals(null, out.host)
    }

    @Test fun eventsGoToTheHostsAndStateChangesAskForAQuery() = runTest {
        host.values[a] = mapOf(ValueKeys.HR to 130L)
        plugin.activate(host)
        plugin.onEvent(WatchEvent.ButtonPressed(a, Instant.parse("2026-11-24T10:15:00Z")))
        plugin.onEvent(WatchEvent.Connected(a, ConnectionKind.WATCH))
        plugin.onEvent(WatchEvent.ValuesUpdated(a, WatchValues(a, mapOf(ValueKeys.HR to 140L)), ValueCause.CONNECTION))
        assertEquals(3, out.events.size)
        assertEquals("CASIO GBX-H5600", out.events[0].variables[Vars.WATCH])
        assertTrue("seeded from the last values: 130 to 140 is no crossing",
            !EventMapper.matches(EventConfig(EventKind.HR_ABOVE, WatchChoice.Connected, 120), out.events[2]))
        assertEquals(2, out.states)
    }

    @Test fun noEventsAfterDeactivate() = runTest {
        plugin.activate(host)
        plugin.deactivate()
        plugin.onEvent(WatchEvent.ButtonPressed(a, Instant.EPOCH))
        assertTrue(out.events.isEmpty())
    }
}
