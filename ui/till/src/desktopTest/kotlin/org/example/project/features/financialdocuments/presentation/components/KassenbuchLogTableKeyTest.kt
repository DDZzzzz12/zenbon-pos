package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-09-27 emulator finding E-1: every cash book numbers its Belege from `YYYY-MM-0001`, so the
 * fiscal Ladenkasse and a non-fiscal Tresor both hold `2026-09-0001`. The log table keyed its
 * LazyColumn by belegNr and crashed with "Key … was already used" on every opening.
 */
@OptIn(ExperimentalTestApi::class)
class KassenbuchLogTableKeyTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun entry(
        headerId: Long,
        cashBookId: Long,
        belegtext: String,
    ) = KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = headerId,
        cashBookId = cashBookId,
        belegNr = "2026-09-0001",
        entryType = KassenbuchEntryType.entries.first(),
        belegtext = belegtext,
        belegDatum = "01.09.2026",
        waehrung = "EUR",
        totalAmount = "10.00",
        lineItems = emptyList(),
        currentBalance = "10.00",
        employeeNumber = "1",
        createdAt = "2026-09-01T10:00:00",
    )

    @Test
    fun `two cash books sharing a belegNr render both rows instead of crashing`() =
        runComposeUiTest {
            setContent {
                KassenbuchLogTable(
                    entries =
                        listOf(
                            entry(headerId = 1L, cashBookId = 1L, belegtext = "Ladenkasse Einlage"),
                            entry(headerId = 2L, cashBookId = 2L, belegtext = "Tresor Einlage"),
                        ),
                    language = Language.GERMAN,
                    isLoading = false,
                    searchTerm = "",
                    onSearchTermChanged = {},
                    onEntryClick = {},
                )
            }
            assertEquals(1, onAllNodesWithText("Ladenkasse Einlage", substring = true).fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithText("Tresor Einlage", substring = true).fetchSemanticsNodes().size)
        }
}
