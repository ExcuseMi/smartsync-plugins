package org.avmedia.smartsync.plugin.trmnl

import org.avmedia.smartsync.plugin.api.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Repo rules: the versions agree and the host finds the plugin (the text rules of the whole repository: homeassistant RepoGuardTest). */
class RepoGuardTest {

    @Test fun theBuildFileAndThePluginNameTheSameVersion() {
        val declared = Regex("""(?m)^version = '([^']+)'""").find(File("build.gradle").readText())?.groupValues?.get(1)
        assertEquals(TrmnlPlugin.VERSION, declared)
        assertTrue(PluginApi.compatible(TrmnlPlugin().apiVersion))
    }

    @Test fun theServiceFileNamesThePlugin() {
        val f = File("src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin")
        assertEquals(TrmnlPlugin::class.java.name, f.readText().trim())
    }
}
