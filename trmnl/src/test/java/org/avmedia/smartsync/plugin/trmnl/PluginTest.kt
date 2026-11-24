package org.avmedia.smartsync.plugin.trmnl

import kotlinx.coroutines.test.runTest
import org.avmedia.smartsync.plugin.api.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The plugin on plugin-api 0.1.0: the periodic wake-up sends what changed, and its details page. */
class PluginTest {
    private val url = "https://trmnl.com/api/custom_plugins/00000000-0000-0000-0000-000000000000"

    @Test fun aWakeSendsWhatChangedAtOnceAndNothingWhenUnchanged() = runTest {
        val host = FakeHost()
        val net = FakeTransport()
        val clock = FakeClock()
        val w = host.addWatch("gshock-aabbccddeeff")
        host.values[w]!!["health.steps_today"] = 1_000
        Settings(enabled = true, sharedUrl = url).save(host)
        val plugin = TrmnlPlugin(net) { clock.now }
        plugin.activate(host)
        assertEquals(TrmnlPlugin.NEEDS, host.needs)
        assertEquals(TrmnlPlugin.WAKE_MINUTES, host.needs!!.wakeEveryMinutes)

        plugin.onEvent(WatchEvent.Wake)
        assertEquals("sent within the wake-up, not a minute later", 1, net.posts.size)
        assertTrue(net.posts.single().second.contains("\"st\":1000"))

        clock.advanceMin(30)
        plugin.onEvent(WatchEvent.Wake)
        assertEquals("unchanged: nothing", 1, net.posts.size)

        host.values[w]!!["health.steps_today"] = 2_000
        plugin.onEvent(WatchEvent.Wake)
        assertEquals(2, net.posts.size)
        plugin.deactivate()
    }

    @Test fun theDetailsPageHasItsMetadataAndAGuide() {
        val p = TrmnlPlugin()
        listOf(p.summary, p.description, p.setupNeeds, p.author, p.license).forEach { assertTrue(it.isNotBlank()) }
        assertTrue("2 to 3 sentences: ${p.description}", p.description.split(". ").size in 2..3)
        listOf(p.infoUrl, p.sourceUrl, p.issuesUrl).forEach { assertTrue(it!!.startsWith("https://github.com/ExcuseMi/smartsync-plugins/")) }
        assertTrue(p.infoUrl!!.endsWith("/docs/trmnl.md"))
        assertTrue(File("../docs/trmnl.md").isFile)
    }
}
