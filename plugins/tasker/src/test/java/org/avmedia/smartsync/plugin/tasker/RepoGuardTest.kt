package org.avmedia.smartsync.plugin.tasker

import org.avmedia.smartsync.plugin.api.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Repo rules: versions agree, the host finds the plugin, it reaches the app only through plugin-api, the manifest names real classes. */
class RepoGuardTest {
    private val module = File(".").canonicalFile

    @Test fun theBuildFileAndThePluginNameTheSameVersion() {
        val declared = Regex("""(?m)^version = '([^']+)'""").find(File("build.gradle").readText())?.groupValues?.get(1)
        assertEquals(TaskerPlugin.VERSION, declared)
        assertTrue(PluginApi.compatible(TaskerPlugin().apiVersion))
        assertTrue(File(module, "CHANGELOG.md").readText().contains("## ${TaskerPlugin.VERSION}"))
    }

    @Test fun theServiceFileNamesThePlugin() {
        val f = File(module, "src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin")
        assertEquals(TaskerPlugin::class.java.name, f.readText().trim())
    }

    @Test fun sourcesImportNoAppOrLibraryClass() {
        val sources = File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 5)
        val allowed = listOf("org.avmedia.smartsync.plugin.api.", "org.avmedia.smartsync.plugin.tasker.")
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val imp = l.trim().takeIf { it.startsWith("import ") }?.removePrefix("import ") ?: return@filter false
                (imp.startsWith("org.avmedia.") && allowed.none { imp.startsWith(it) }) || imp.contains("gshockapi", ignoreCase = true) ||
                    imp.startsWith("com.joaomgcd") || imp.startsWith("com.twofortyfouram")
            }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue("reach-in past plugin-api or a GPL plugin library:\n" + hits.joinToString("\n"), hits.isEmpty())
        val build = File(module, "build.gradle").readText()
        assertEquals(setOf(":plugin-api"), Regex("""project\('([^']+)'\)""").findAll(build).map { it.groupValues[1] }.toSet())
    }

    @Test fun theManifestNamesClassesThatExistAndTheProtocolActions() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        val names = Regex("""android:name="(org\.avmedia\.[^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(5, names.size)
        names.forEach { Class.forName(it, false, javaClass.classLoader) }
        listOf(Protocol.ACTION_EDIT_EVENT, Protocol.ACTION_EDIT_CONDITION, Protocol.ACTION_EDIT_SETTING, Protocol.ACTION_FIRE_SETTING, Protocol.ACTION_QUERY_CONDITION)
            .forEach { assertTrue(it, manifest.contains("\"$it\"")) }
    }

    @Test fun theGuideLinkPointsAtTheGuide() {
        val url = TaskerPlugin().infoUrl!!
        val path = url.substringAfter("/blob/main/")
        assertTrue(url, File(module.parentFile.parentFile, path).isFile)
    }
}
