package org.avmedia.smartsync.plugin.tasker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatesTest {
    private val host = FakeHost()
    private val a = host.addWatch("AA:01", "CASIO GBX-H5600", connected = true)
    private val b = host.addWatch("BB:02", "CASIO GW-B5600", connected = false)
    private fun ask(kind: EventKind, t: Long? = null, watch: WatchChoice = WatchChoice.Connected) =
        States.query(EventConfig(kind, watch, t, state = true), host.watches()) { host.lastValues(it) }

    @Test fun connectedIsTheWatchsLink() {
        assertEquals(true, ask(EventKind.CONNECTED).holds)
        assertEquals(false, ask(EventKind.CONNECTED, watch = WatchChoice.Specific(b, "b")).holds)
        assertEquals("CASIO GBX-H5600", ask(EventKind.CONNECTED).variables[Vars.WATCH])
    }

    @Test fun levelsComeFromTheLastValues() {
        host.values[a] = mapOf(ValueKeys.BATTERY to 15L, ValueKeys.HR to 72.0, ValueKeys.STEPS to 10_200L, ValueKeys.STEP_GOAL to 10_000L)
        assertEquals(true, ask(EventKind.BATTERY_BELOW, 20).holds)
        assertEquals(false, ask(EventKind.BATTERY_BELOW, 10).holds)
        assertEquals(true, ask(EventKind.HR_BELOW, 80).holds)
        assertEquals(false, ask(EventKind.HR_ABOVE, 80).holds)
        assertEquals(true, ask(EventKind.STEP_GOAL).holds)
        assertEquals("72", ask(EventKind.HR_ABOVE, 80).variables[Vars.HR])
    }

    @Test fun noValueOrNoWatchIsUnknown() {
        assertNull(ask(EventKind.BATTERY_BELOW, 20).holds)
        host.paired.replaceAll { it.copy(connected = false) }
        assertEquals("nothing connected", false, ask(EventKind.CONNECTED).holds)
        assertNull(ask(EventKind.HR_ABOVE, 100).holds)
    }
}
