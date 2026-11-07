package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.core.utils.LocalLanguageManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Emulator pass 4, NOTE-6: the till's Z-Bon mixed "148.00 EUR", "22.37 €" and "2,50 €" on one
 * screen. Every amount must come out of the one display formatter, and the VAT rate as "19 %".
 */
@OptIn(ExperimentalTestApi::class)
class ZBonDetailPanelMoneyFormatTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private val zBon =
        ZBonDto.ZBonResponseDto(
            id = 1L,
            zbonNummer = 1L,
            shopInfoId = 1L,
            shopName = "Test Shop",
            employeeNumber = "M001",
            startTime = "2026-09-28T08:00:00",
            endTime = "2026-09-28T22:00:00",
            cancelledAmount = "0.00",
            totalDiscounts = "2.50",
            adminTip = "1.20",
            totalRevenue = "1148.00",
            openingBalance = "0.00",
            expenses = "12.50",
            closingBalance = "155.30",
            createdAt = "2026-09-28T22:00:00",
            vatGroups =
                listOf(
                    ZBonDto.VatGroupResponseDto(
                        vatRate = "19.00",
                        vatClass = "A",
                        netAmount = "117.53",
                        vatAmount = "22.37",
                        grossAmount = "139.90",
                    ),
                ),
            paymentSummary = listOf(ZBonDto.PaymentsSummaryResponseDto(paymentName = "Bar", currency = "EUR", amount = "1148.00")),
            employeeSummaries =
                listOf(
                    ZBonDto.EmployeeZBonSummaryResponseDto(
                        employeeNumber = "K001",
                        role = "CASHIER",
                        amount = "0.0000",
                        totalRevenue = "73.10",
                        paymentBreakdown =
                            listOf(ZBonDto.EmployeeZBonPaymentBreakdownResponseDto(paymentName = "Bar", currency = "EUR", amount = "73.10")),
                    ),
                ),
            kassenbuchSummaryDto = null,
        )

    private fun SemanticsNodeInteractionCollection.texts(): List<String> =
        fetchSemanticsNodes().flatMap { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        }

    @Test
    fun `every amount on the Z-Bon uses the display money format`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLanguageManager provides LanguageManager()) {
                    ZBonDetailPanel(zBonData = zBon, onClose = {}, language = Language.GERMAN)
                }
            }
            val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).texts()

            listOf("1.148,00 €", "22,37 €", "117,53 €", "139,90 €", "73,10 €", "2,50 €", "1,20 €", "155,30 €", "19 %")
                .forEach { expected -> assertTrue(texts.any { it == expected }, "expected \"$expected\" on the Z-Bon, got $texts") }
            // Dates use the shared dd.MM.yyyy HH:mm format, as the back office's panel does.
            assertTrue(texts.any { it == "28.09.2026 22:00" }, "expected the German date-time, got $texts")
            // A German date ("28.09.2026 22:00") is not a raw amount: strip it before looking for "1.50".
            val germanDate = Regex("""\b\d{2}\.\d{2}\.\d{4}\b""")
            val raw =
                texts.filter {
                    it.contains("EUR") || Regex("""\d\.\d\d\b""").containsMatchIn(it.replace(germanDate, "")) || it.endsWith(".00%")
                }
            assertTrue(raw.isEmpty(), "unformatted amounts on the Z-Bon: $raw")
        }

    private fun textsOf(data: ZBonDto.ZBonResponseDto): List<String> {
        var texts = emptyList<String>()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLanguageManager provides LanguageManager()) {
                    ZBonDetailPanel(zBonData = data, onClose = {}, language = Language.GERMAN)
                }
            }
            texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).texts()
        }
        return texts
    }

    @Test
    fun `a Zahlartwechsel Storno is broken out under the refunds`() {
        val texts =
            textsOf(
                zBon.copy(refundAmount = "33.00", stornoBillCount = 2, rebookStornoCount = 1, rebookStornoAmount = "23.00"),
            )

        assertTrue(texts.any { it.startsWith("Rückerstattungen") && it.endsWith("(2)") }, "refunds row: $texts")
        assertTrue(texts.any { it == "davon Zahlartwechsel (1)" }, "expected the davon-Zahlartwechsel row, got $texts")
        assertTrue(texts.any { it == "23,00 €" }, "the Zahlartwechsel amount in the display money format, got $texts")
    }

    @Test
    fun `no Zahlartwechsel row without a Zahlartwechsel`() {
        val texts = textsOf(zBon.copy(refundAmount = "10.00", stornoBillCount = 1))

        assertTrue(texts.none { it.contains("Zahlartwechsel") }, "no davon-Zahlartwechsel row expected, got $texts")
    }
}
