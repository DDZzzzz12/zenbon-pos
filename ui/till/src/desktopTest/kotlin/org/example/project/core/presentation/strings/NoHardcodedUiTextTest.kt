package org.example.project.core.presentation.strings

import org.example.project.testutils.SourceRoots
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E2E-2026-09-18 #13/#18/#21: "1 items" on the German till, "2 total" on the Chinese print-job panel,
 * untranslated content descriptions. Every UI word goes through `str(key)` (ui/till/CLAUDE.md).
 * A source scan over composeApp and shared-ui commonMain, since each of these was a call-site slip.
 */
class NoHardcodedUiTextTest {
    private companion object {
        /** Kotlin files under [roots] on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 854
    }

    /** ui-till and ui-shared commonMain; neither moves in the platform extraction. */
    private val roots = listOf(SourceRoots.tillRoot(), SourceRoots.uiSharedRoot())

    /** `text = "Letters…"` / `contentDescription = "Letters…"`, no template at the start. */
    private val literal = Regex("""\b(text|contentDescription)\s*=\s*"[A-Za-zÄÖÜäöü][^"$]*"""")

    /** A template followed by an English word: `text = "$count items"`. */
    private val templatedWord = Regex("""\b(text|contentDescription)\s*=\s*"\$\{?[A-Za-z.()]+\}? [a-z]+""")

    /**
     * Deliberate non-words: a one-letter badge is a symbol, not text. Each entry names why.
     */
    private val allowed =
        listOf(
            "text = \"G\"", // VoucherCodeInput: a one-letter badge, not a word
        )

    private fun scannedFiles(): List<File> =
        SourceRoots.kotlinFiles(roots).also { SourceRoots.requireScanned("NoHardcodedUiTextTest", it.size, MINIMUM_SCANNED_FILES) }

    private fun offenders(pattern: Regex): List<String> =
        scannedFiles()
            .filter { !it.name.endsWith("Strings.kt") }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    val hit =
                        pattern.containsMatchIn(line) &&
                            !trimmed.startsWith("//") &&
                            !trimmed.startsWith("*") &&
                            allowed.none { trimmed.contains(it) }
                    if (hit) "${f.path}:${i + 1}: $trimmed" else null
                }
            }

    @Test
    fun noComposablePassesAHardcodedLetterLiteralAsTextOrContentDescription() {
        val found = offenders(literal)
        assertTrue(found.isEmpty(), "hardcoded UI text:\n" + found.joinToString("\n"))
    }

    @Test
    fun noComposableBuildsTextFromATemplateAndAnEnglishWord() {
        val found = offenders(templatedWord)
        assertTrue(found.isEmpty(), "templated English UI text:\n" + found.joinToString("\n"))
    }
}
