package org.avmedia.smartsync.plugin.homeassistant

import org.avmedia.smartsync.plugin.api.PluginApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Repo rules, as in the app: versions agree, the plugin is found by the host, it reaches the app only through
 * plugin-api, and no text has private references, AI lines or em-dashes.
 */
class RepoGuardTest {
    private val module = File(".").canonicalFile
    private val repo = File("..").canonicalFile

    @Test fun theBuildFileAndThePluginNameTheSameVersion() {
        val declared = Regex("""(?m)^version = '([^']+)'""").find(File("build.gradle").readText())?.groupValues?.get(1)
        assertEquals(HomeAssistantPlugin.VERSION, declared)
        assertTrue(PluginApi.compatible(HomeAssistantPlugin().apiVersion))
        assertTrue(File(module, "CHANGELOG.md").readText().contains("## ${HomeAssistantPlugin.VERSION}"))
    }

    @Test fun theServiceFileNamesThePlugin() {
        val f = File(module, "src/main/resources/META-INF/services/org.avmedia.smartsync.plugin.api.WatchPlugin")
        assertEquals(HomeAssistantPlugin::class.java.name, f.readText().trim())
    }

    @Test fun sourcesImportNoAppOrLibraryClass() {
        val sources = File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 5)
        val allowed = listOf("org.avmedia.smartsync.plugin.api.", "org.avmedia.smartsync.plugin.homeassistant.")
        val hits = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val t = l.trim()
                val imp = if (t.startsWith("import ")) t.removePrefix("import ") else return@filter false
                (imp.startsWith("org.avmedia.") && allowed.none { imp.startsWith(it) }) ||
                    imp.startsWith("io.github.izivkov") || imp.contains("gshockapi", ignoreCase = true)
            }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertTrue("reach-in past plugin-api:\n" + hits.joinToString("\n"), hits.isEmpty())
        val build = File(module, "build.gradle").readText()
        assertEquals(setOf(":plugin-api"), Regex("""project\('([^']+)'\)""").findAll(build).map { it.groupValues[1] }.toSet())
    }

    /** The one text guard of the repository: every module and the root files (plugins and plugin-api alike). */
    @Test fun noFileHasPrivateReferencesAiLinesOrEmDashes() {
        val pattern = Regex(
            listOf(
                """notes?,? section""", """\(notes""", """\bnotes [A-Z][0-9]?\b""", """the notes""", """casio-protocol-notes""",
                """Casio.s app""", """official app""", """decompil""", """jadx""", """\bDart\b""", """gshock-briefs""",
                """\bCasio('|’)?s? (own |official )?(app|setup|does|waits|writes)""", """CASIO WATCHES""", """\bas Casio\b""",
                """AsCasio""", """\bCasio:""",
                """\bowner'?s?\s+(log|logs|screenshot|screenshots|dump|hint|test|reads|watch|capture)\b""",
                """\b(owner|user)\b,?\s+20\d\d-\d\d-\d\d""", """\((owner|user),?\s*20\d\d""",
                """\blogs?\s+(of\s+)?20\d\d-\d\d-\d\d""", """\buser'?s (capture|log|screenshot|watch)\b""", """parse_\w+\.py""",
                """Generated with""", """Co-Authored-By""", """noreply@anthropic""", """\bClaude\b""", """—""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
        val ext = setOf("kt", "kts", "java", "md", "yml", "yaml", "gradle", "txt", "json", "xml", "properties", "pro", "toml")
        // From the class, so a renamed package cannot make the guard scan (and refuse) its own patterns.
        val self = File(module, "src/test/java/" + RepoGuardTest::class.java.name.replace('.', '/') + ".kt").canonicalFile
        assertTrue("guard file not found: $self", self.isFile)
        val files = repo.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "vendor", ".kotlin") }
            .filter { it.isFile && it.extension in ext && it.canonicalFile != self }.toList()
        assertTrue("the scan found too few files, wrong working directory? ${repo.path}", files.size > 15)
        val hits = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> pattern.containsMatchIn(l) }
                .map { (i, l) -> "${f.relativeTo(repo).invariantSeparatorsPath}:${i + 1}: ${l.trim()}" }
        }
        assertTrue("Private references, AI lines or em-dashes:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
