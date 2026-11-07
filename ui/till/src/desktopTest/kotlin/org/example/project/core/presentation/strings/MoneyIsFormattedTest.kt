package org.example.project.core.presentation.strings

import org.example.project.testutils.SourceRoots
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E2E-2026-09-18 #14: server money strings printed verbatim — "€50.00", "155.40€", "Einzelpreis:
 * 14.50" next to "14,50 €". Every displayed amount goes through `formatMoneyForDisplay`.
 * A source scan, since each one was a call-site slip.
 */
class MoneyIsFormattedTest {
    private companion object {
        /** Kotlin files under [roots] on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 854
    }

    /** ui-till and ui-shared commonMain; neither moves in the platform extraction. */
    private val roots = listOf(SourceRoots.tillRoot(), SourceRoots.uiSharedRoot())

    private val patterns =
        listOf(
            Regex(""""€\$"""), // "€${amount}"
            Regex("""\+ "€""""), // amount + "€"
            Regex("""unitPrice\}""""), // "... ${item.unitPrice}"
            Regex("""text = item\.unitPrice,"""),
            Regex("""Text\(item\.unitPrice[,)]"""),
        )

    @Test
    fun noMoneyStringIsRenderedWithoutTheFormatter() {
        val files = SourceRoots.kotlinFiles(roots)
        SourceRoots.requireScanned("MoneyIsFormattedTest", files.size, MINIMUM_SCANNED_FILES)
        val offenders =
            files.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val hit = patterns.any { it.containsMatchIn(line) } && !line.trim().startsWith("//")
                    if (hit) "${f.path}:${i + 1}: ${line.trim()}" else null
                }
            }
        assertTrue(offenders.isEmpty(), "money rendered without formatMoneyForDisplay:\n" + offenders.joinToString("\n"))
    }
}
