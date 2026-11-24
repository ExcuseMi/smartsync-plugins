package org.avmedia.smartsync.plugin.healthconnect

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The same rules as the app's guard: say what the watch does in our own words, never point at private notes or another
 * app, never say where a decoding came from. Also no em-dashes. Scans the whole repository.
 */
class PrivateReferencesTest {
    private val pattern = Regex(
        listOf(
            """notes?,? section""", """\(notes""", """\bnotes [A-Z][0-9]?\b""", """the notes""", """casio-protocol-notes""",
            """Casio.s app""", """official app""", """decompil""", """jadx""", """\bDart\b""",
            """\bCasio('|’)?s? (own |official )?(app|setup|does|waits|writes)""", """CASIO WATCHES""", """\bas Casio\b""",
            """AsCasio""", """\bCasio:""",
            """\bowner'?s?\s+(log|logs|screenshot|screenshots|dump|hint|test|reads|watch|capture)\b""",
            """\b(owner|user)\b,?\s+20\d\d-\d\d-\d\d""", """\((owner|user),?\s*20\d\d""",
            """\blogs?\s+(of\s+)?20\d\d-\d\d-\d\d""", """\buser'?s (capture|log|screenshot|watch)\b""", """parse_\w+\.py""",
            """Generated with""", """Co-Authored-By""", """\bClaude\b""", """\u2014""",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
    private val textExtensions = setOf("kt", "kts", "java", "md", "yml", "yaml", "gradle", "txt", "json", "xml", "properties", "pro", "toml")

    @Test fun noFileHasPrivateReferencesAiLinesOrEmDashes() {
        val repo = File("..").canonicalFile
        val self = File("src/test/java/org/avmedia/smartsync/plugin/healthconnect/PrivateReferencesTest.kt").canonicalFile
        val files = repo.walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "vendor", ".kotlin") }
            .filter { it.isFile && it.extension in textExtensions && it.canonicalFile != self }.toList()
        assertTrue("the scan found too few files, wrong working directory? ${repo.path}", files.size > 15)
        val hits = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> pattern.containsMatchIn(l) }
                .map { (i, l) -> "${f.relativeTo(repo).invariantSeparatorsPath}:${i + 1}: ${l.trim()}" }
        }
        assertTrue("Private references, AI lines or em-dashes:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
