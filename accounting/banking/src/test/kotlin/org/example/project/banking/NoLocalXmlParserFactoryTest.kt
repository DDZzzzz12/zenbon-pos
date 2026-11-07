package org.example.project.banking

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CAMT.053 arrives from a bank as third-party XML, so every byte of it goes through `SecureXml` in
 * `:platform-secure-xml`. This module must contain no XML parser factory of its own: a second one would
 * parse a supplier's file with doctypes and external entities enabled, and the statement would still
 * map correctly — it would just also resolve whatever the sender asked it to read.
 */
class NoLocalXmlParserFactoryTest {
    private val forbiddenFactories = listOf("DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory")

    @Test
    fun `no file in this module builds an XML parser`() {
        val sources = mainKotlinSources()
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
