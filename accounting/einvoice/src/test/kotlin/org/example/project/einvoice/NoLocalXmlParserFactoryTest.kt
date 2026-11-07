package org.example.project.einvoice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SecureXml` now lives in `:platform-secure-xml`, so this module must contain **no** XML parser factory at
 * all — not even a permitted one. A mapper or detector added later could construct its own in a few
 * lines, parse supplier-sent XML with every protection off, and no other test here would go red:
 * the invoice would still map correctly, it would just also resolve whatever the sender asked it to
 * read. This makes that failure loud instead of invisible.
 */
class NoLocalXmlParserFactoryTest {
    private val forbiddenFactories = listOf("DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory")

    @Test
    fun `no file in this module builds an XML parser`() {
        val sources = mainKotlinSources()
        // Guard the guard: if the tree walk silently found nothing, the assertion below is vacuous.
        assertTrue(sources.size >= 2, "expected to scan the module's main sources, found ${sources.size} files")

        val offenders =
            sources
                .filter { file -> forbiddenFactories.any { it in file.readText().withoutComments() } }
                .map { it.name }

        assertEquals(
            emptyList(),
            offenders,
            "these files build their own XML parser and bypass SecureXml's hardening: $offenders",
        )
    }

    /**
     * Scanning raw text would fire on the very comment a careful developer writes — "do not build a
     * `DocumentBuilderFactory` here, use `SecureXml`" — so comments are removed before the scan.
     * Detection is not weakened: what remains is code, and code cannot construct a factory without
     * naming its type. The line-comment pattern deliberately ignores a `//` preceded by a colon so
     * that a URL in a string literal does not truncate the rest of a real line.
     */
    private fun String.withoutComments(): String =
        this
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?<!:)//[^\n]*"""), " ")

    private fun mainKotlinSources(): List<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "cannot find ${root.absolutePath}; test working dir is ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
