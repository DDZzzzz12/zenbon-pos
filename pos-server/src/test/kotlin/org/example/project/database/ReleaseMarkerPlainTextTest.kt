package org.example.project.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.dto.changelog.ChangelogSection
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release-note bullets are plain text, because the in-app "What's new" panel renders them as plain
 * text.
 *
 * `ReleaseNoteList` in shared-ui prints every entry of a marker's `highlights_*` / `known_issues_*`
 * arrays verbatim behind a "• ", and understands exactly one piece of markup: an entry starting
 * with [ChangelogSection.HEADING_PREFIX] is a section heading. Anything else — `**bold**`,
 * `` `code` ``, links, list markers — reaches the operator as literal punctuation. The v1.1.1
 * marker shipped 39 `**bold**` lead phrases that way before this test existed; GitHub rendered
 * them, the panel showed the asterisks.
 *
 * Reads every marker `SchemaManager` would seed, so a new release is covered without an edit. No
 * marker is allow-listed: every one on disk (V80, V90, V92) is clean.
 *
 * Needs no database — the markers are classpath resources and the arrays are JSON literals.
 */
@DisplayName("Release-marker notes contain no Markdown the in-app panel cannot render")
class ReleaseMarkerPlainTextTest {
    @Test
    fun `no release-note entry carries Markdown the panel would print literally`() {
        val markers = SchemaManager.releaseMarkerScripts()
        assertTrue(markers.isNotEmpty(), "no release markers found — this test has lost its subject")

        val findings =
            markers.flatMap { marker ->
                noteArrays(marker.script).flatMap { entry ->
                    markdownIn(entry).map { kind -> "${marker.script}: $kind in \"${entry.take(80)}\"" }
                }
            }

        assertTrue(
            findings.isEmpty(),
            "the What's new panel renders release notes as plain text and only understands the " +
                "\"${ChangelogSection.HEADING_PREFIX}\" heading prefix; these entries would show " +
                "their Markdown literally:\n" + findings.joinToString("\n"),
        )
    }

    @Test
    fun `the detector flags each Markdown form and leaves plain text alone`() {
        // Negative control: without it a detector that matched nothing would pass every marker.
        val flagged =
            listOf(
                "**Lead.** Rest of the bullet",
                "__Lead.__ Rest of the bullet",
                "Run `install-service.sh` again",
                "See [the manual](https://example.org)",
                "### Too deep a heading",
                "# Too shallow a heading",
                "- a nested list item",
                "* a nested list item",
                "1. a numbered item",
                "> a quotation",
                "an *emphasised* word",
                "## Heading with **bold** inside",
            )
        flagged.forEach { entry ->
            assertTrue(markdownIn(entry).isNotEmpty(), "not flagged: \"$entry\"")
        }

        val plain =
            listOf(
                "## New and updated: closing the day",
                "Close the day from the admin panel. Z-Bon creation now lives on the dashboard.",
                "The marker V92__release_1_1_1.sql is the source of these notes.",
                "Set MAINTAINER_USER and MAINTAINER_PASSWORD to enable it.",
                "Rounding: 2 * 3 is still 6, and C# stays C#.",
                "可从网页后台日结。创建 Z-Bon 现在位于后台仪表盘。",
            )
        plain.forEach { entry ->
            assertEquals(emptyList(), markdownIn(entry), "wrongly flagged: \"$entry\"")
        }
    }

    /** Every entry of the six `'[...]'::jsonb` note arrays of one marker, in order. */
    private fun noteArrays(script: String): List<String> {
        val sql =
            checkNotNull(javaClass.classLoader.getResource("db/migration/$script")) { "$script is not on the classpath" }
                .readText()
        val insert = sql.substring(sql.indexOf("INSERT INTO changelog_release"))
        val arrays = jsonbLiterals(insert)
        assertEquals(6, arrays.size, "$script: expected the six highlights/known-issues arrays, found ${arrays.size}")
        return arrays.flatMap { literal -> Json.parseToJsonElement(literal).jsonArray.map { it.jsonPrimitive.content } }
    }

    /**
     * The `'...'::jsonb` literals of one statement, unescaped, walked under SQL quoting rules (`''`
     * is one quote). A walk rather than a regex: an alternation over 10 kB literals overflows the
     * JVM regex engine's stack.
     */
    private fun jsonbLiterals(sql: String): List<String> {
        val literals = mutableListOf<String>()
        var i = 0
        while (i < sql.length) {
            if (sql[i] != '\'') {
                i++
                continue
            }
            val text = StringBuilder()
            var j = i + 1
            while (true) {
                check(j < sql.length) { "unterminated SQL literal starting at offset $i" }
                if (sql[j] == '\'') {
                    if (j + 1 < sql.length && sql[j + 1] == '\'') {
                        text.append('\'')
                        j += 2
                        continue
                    }
                    break
                }
                text.append(sql[j])
                j++
            }
            if (sql.startsWith("::jsonb", j + 1)) literals += text.toString()
            i = j + 1
        }
        return literals
    }

    /** The kinds of unrendered Markdown in one entry; empty when the panel shows it as written. */
    private fun markdownIn(entry: String): List<String> {
        val body = if (ChangelogSection.isHeading(entry)) ChangelogSection.headingText(entry) else entry
        return BLOCK_MARKUP.filter { (_, pattern) -> pattern.containsMatchIn(body) }.map { it.first } +
            INLINE_MARKUP.filter { (_, pattern) -> pattern.containsMatchIn(body) }.map { it.first }
    }

    private companion object {
        /** Only checked at the start of an entry (or of a heading's text). */
        val BLOCK_MARKUP =
            listOf(
                "a heading other than \"## \"" to Regex("""^#{1,6}\s"""),
                "a list marker" to Regex("""^\s*(?:[-*+]|\d+\.)\s"""),
                "a block quote" to Regex("""^\s*>"""),
            )

        val INLINE_MARKUP =
            listOf(
                "**bold**" to Regex("""\*\*"""),
                "__bold__" to Regex("""(?<![\w_])__(?=\S).+?(?<=\S)__(?![\w_])"""),
                "`code`" to Regex("""`"""),
                "a [link](url)" to Regex("""\[[^\]]*]\([^)]*\)"""),
                "*emphasis*" to Regex("""(?<![\w*])\*(?=\S)[^*]+?(?<=\S)\*(?![\w*])"""),
            )
    }
}
