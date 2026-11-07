package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.LocalLanguageManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA emu14 NOTE-5: the tip block listed M001 4 + A001 4 + K001 2 under "Umsatz pro Mitarbeiter" and
 * totalled 6.00, because the owner's tip (revenue) was listed but, correctly, not summed. The block
 * now lists employee tips only, under a tip heading, and shows the owner tip on its own line.
 */
@OptIn(ExperimentalTestApi::class)
class ZBonDetailPanelTipBlockTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun summary(
        number: String,
        tip: String,
        owner: Boolean = false,
    ) = ZBonDto.EmployeeZBonSummaryResponseDto(
        employeeNumber = number,
        role = if (owner) "ADMIN" else "CASHIER",
        amount = tip,
        totalRevenue = "10.00",
        ownerTip = owner,
    )

    private fun zBon(ownerMarked: Boolean) =
        ZBonDto.ZBonResponseDto(
            id = 1L,
            zbonNummer = 1L,
            shopInfoId = 1L,
            shopName = "Test Shop",
            employeeNumber = "M001",
            startTime = "2026-10-01T08:00:00",
            endTime = "2026-10-01T22:00:00",
            cancelledAmount = "0.00",
            adminTip = "4.00",
            totalRevenue = "298.10",
            openingBalance = "0.00",
            expenses = "0.00",
            closingBalance = "0.00",
            createdAt = "2026-10-01T22:00:00",
            employeeSummaries =
                listOf(summary("M001", "4.50"), summary("A001", "4.00", owner = ownerMarked), summary("K001", "2.00")),
            totalEmployeeTips = "6.50",
            kassenbuchSummaryDto = null,
        )

    private fun textsOf(data: ZBonDto.ZBonResponseDto): List<String> {
        var texts = emptyList<String>()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLanguageManager provides LanguageManager()) {
                    ZBonDetailPanel(zBonData = data, onClose = {}, language = Language.GERMAN)
                }
            }
            texts =
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { node ->
                        node.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { it.text }
                    }
        }
        return texts
    }

    @Test
    fun `the tip block lists employee tips only, totals them, and shows the owner tip apart`() {
        val texts = textsOf(zBon(ownerMarked = true))

        assertTrue("Trinkgeld pro Mitarbeiter" in texts, "the tip block is headed as tips: $texts")
        assertTrue("Umsatz pro Mitarbeiter" !in texts, "no tip block headed as revenue: $texts")
        assertTrue("Trinkgeld gesamt" in texts && "6,50 €" in texts, "the employee total is 4.50 + 2.00: $texts")
        val ownerLine = texts.indexOf("Trinkgeld Betrieb (im Umsatz enthalten)")
        assertTrue(ownerLine >= 0, "the owner tip has its own, revenue-labelled line: $texts")
        assertEquals("4,00 €", texts[ownerLine + 1], "the owner tip's amount follows its label: $texts")
    }

    @Test
    fun `admin17 NOTE-15 - the revenue block lists the owner tip as its own row, before the tip block`() {
        val texts = textsOf(zBon(ownerMarked = true))

        val ownerRow = texts.indexOf("Inhabertrinkgeld")
        assertTrue(ownerRow >= 0, "the revenue block has an owner-tip row: $texts")
        assertEquals("4,00 €", texts[ownerRow + 1], "its amount is the closing's adminTip: $texts")
        assertTrue(ownerRow < texts.indexOf("Trinkgeld pro Mitarbeiter"), "it belongs to the revenue block: $texts")
    }

    @Test
    fun `the owner is not listed among the employee tips`() {
        val marked = textsOf(zBon(ownerMarked = true)).count { it == "A001" }
        val unmarked = textsOf(zBon(ownerMarked = false)).count { it == "A001" }

        assertEquals(unmarked - 1, marked, "A001's tip row is dropped from the employee list when it is the owner's")
    }

    // admin20 NOTE-B: the closing's Schulungsbelege are reported apart from revenue, and shown only when present.
    @Test
    fun `training receipts get their own line, outside revenue, only when there are any`() {
        val texts = textsOf(zBon(ownerMarked = true).copy(trainingRevenue = "7.40", trainingBillCount = 5))
        val line = texts.indexOf("Schulungsbelege (5, kein Umsatz)")
        assertTrue(line >= 0, "the training line is shown: $texts")
        assertEquals("7,40 €", texts[line + 1], "its amount is trainingRevenue: $texts")
        assertTrue(textsOf(zBon(ownerMarked = true)).none { it.startsWith("Schulungsbelege") }, "no line without training receipts")
    }
}
