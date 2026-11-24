package org.avmedia.smartsync.plugin.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The plugin sees the app only through plugin-api, and ships what Health Connect and the host need to find it. */
class PluginBoundaryTest {
    private val module = File(".").canonicalFile
    private val sources = File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun sourcesImportNoAppOrLibraryClass() {
        assertTrue("found no sources in ${module.path}", sources.size > 5)
        val allowed = listOf("org.avmedia.smartsync.plugin.api.", "org.avmedia.smartsync.plugin.healthconnect.")
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val imp = l.trim().removePrefix("import ").takeIf { l.trim().startsWith("import ") } ?: return@filter false
                (imp.startsWith("org.avmedia.") && allowed.none { imp.startsWith(it) }) ||
                    imp.startsWith("io.github.izivkov") || imp.contains("gshockapi", ignoreCase = true)
            }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue("reach-in past plugin-api:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun theOnlyProjectDependencyIsPluginApi() {
        val build = File(module, "build.gradle").readText()
        val projects = Regex("""project\('([^']+)'\)""").findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf(":plugin-api"), projects)
    }

    @Test fun theHostFindsThePluginThroughServiceLoader() {
        val service = File(module, "src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin")
        assertEquals(HealthConnectPlugin::class.java.name, service.readText().trim())
    }

    @Test fun theManifestAsksWritePermissionForEveryTypeAndReadsNothing() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        RecordType.entries.forEach {
            val p = HealthConnectSink.permission(it)
            assertTrue("manifest lacks $p", manifest.contains("\"$p\""))
        }
        assertTrue("no read permission", !manifest.contains("android.permission.health.READ_"))
    }

    @Test fun theManifestHasTheRationaleHealthConnectRequires() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        listOf(
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
            "android.intent.action.VIEW_PERMISSION_USAGE",
            "android.intent.category.HEALTH_PERMISSIONS",
            "android.permission.START_VIEW_PERMISSION_USAGE",
            RationaleActivity::class.java.name,
            "com.google.android.apps.healthdata",
        ).forEach { assertTrue("manifest lacks $it", manifest.contains(it)) }
    }

    @Test fun theDetailsPageHasItsMetadataAndAGuide() {
        val p = HealthConnectPlugin()
        listOf(p.summary, p.description, p.setupNeeds, p.author, p.license).forEach { assertTrue(it.isNotBlank()) }
        assertTrue("2 to 3 sentences: ${p.description}", p.description.split(". ").size in 2..3)
        listOf(p.infoUrl, p.sourceUrl, p.issuesUrl).forEach { assertTrue(it!!.startsWith("https://github.com/ExcuseMi/smartsync-plugins/")) }
        assertTrue(p.infoUrl!!.endsWith("/docs/healthconnect.md"))
        assertTrue(File(module.parentFile.parentFile, "docs/healthconnect.md").isFile)
    }
}
