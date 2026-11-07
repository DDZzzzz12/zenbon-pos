package org.example.project.pdf.generator

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * QA emu15 NOTE-6, PDF leg: the voucher, promotion and Kassenbuch totals were formatted with
 * `String.format("%.2f", …)`, which follows the JVM default locale — on a German server they read
 * "60,00 EUR" next to "60.00 EUR" lines built from plain decimal strings. Every amount on the PDF
 * Z-Bon must use one separator whatever the default locale is — the German comma since F63.
 */
class GenerateZBonReportLocaleTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private fun kassenbuch(
        id: Long,
        type: KassenbuchEntryType,
        amount: String,
    ) = KassenbuchDto.KassenbuchGroupedSummaryDto(
        headerId = id,
        cashBookId = 1,
        belegNr = "2026-10-000$id",
        entryType = type,
        belegtext = "Beleg $id",
        belegDatum = "01.10.2026",
        waehrung = "EUR",
        totalAmount = amount,
        lineItems = emptyList(),
        currentBalance = amount,
        employeeNumber = "M001",
        createdAt = "2026-10-01T12:00:00",
    )

    private val zBon =
        ZBonDto.ZBonResponseDto(
            id = 1,
            zbonNummer = 1,
            shopInfoId = 1,
            shopName = "Testladen",
            employeeNumber = "A001",
            startTime = "2026-10-01T00:00:00",
            endTime = "2026-10-01T23:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "160.50",
            openingBalance = "0.00",
            expenses = "0.00",
            closingBalance = "0.00",
            createdAt = "2026-10-01T23:00:00",
            vouchers = listOf(VoucherDto.VoucherSummeryDto(1, "G-1", "60.00", "M001")),
            voucherRedeems =
                listOf(
                    VoucherDto.RedeemVoucherResponse(
                        id = 1,
                        voucherNumber = "G-1",
                        voucherSource = VoucherSourceType.INTERNAL,
                        redeemedAmount = "12.50",
                        remainingValue = "47.50",
                        redeemedAt = "2026-10-01T13:00:00",
                        billId = 1,
                    ),
                ),
            totalPromotions = "2.25",
            promotionBreakdown = listOf(ZBonDto.PromotionBreakdownDto(1, "Happy Hour", "PERCENTAGE", 3, "2.25")),
            kassenbuchSummaryDto =
                listOf(
                    kassenbuch(1, KassenbuchEntryType.CASH_SALE, "93.50"),
                    kassenbuch(2, KassenbuchEntryType.CASH_EXPENSE, "14.75"),
                    kassenbuch(3, KassenbuchEntryType.CASH_TRANSFER_OUT, "40.00"),
                    kassenbuch(4, KassenbuchEntryType.CASH_SHORTAGE, "0.30"),
                ),
        )

    @Test
    fun `every EUR amount uses a German comma whatever the JVM default locale`() {
        val previous = Locale.getDefault()
        val text =
            try {
                Locale.setDefault(Locale.US)
                extractText(generateZBonReportPdfBytes(zBon))
            } finally {
                Locale.setDefault(previous)
            }

        val dotAmounts = Regex("""\d+\.\d{2} EUR""").findAll(text).map { it.value }.toList()
        assertTrue(dotAmounts.isEmpty(), "dot-formatted amounts $dotAmounts in:\n$text")
        // The amounts the old String.format sites produced, now with a comma.
        listOf("60,00 EUR", "12,50 EUR", "2,25 EUR", "93,50 EUR", "14,75 EUR", "40,00 EUR", "0,30 EUR").forEach {
            assertTrue(it in text, "$it missing in:\n$text")
        }
    }

    @Test
    fun `emu16 NOTE-9 - a zero Stornierungen or Rabatte shows 0_00 EUR without a minus sign`() {
        val text = extractText(generateZBonReportPdfBytes(zBon))

        assertTrue("-0,00 EUR" !in text, "a zero deduction must not carry a minus:\n$text")
        assertTrue(Regex("""Stornierungen:\s*0,00 EUR""").containsMatchIn(text), text)
        assertTrue(Regex("""Rabatte:\s*0,00 EUR""").containsMatchIn(text), text)

        val nonZero = extractText(generateZBonReportPdfBytes(zBon.copy(cancelledAmount = "4.50")))
        assertTrue(Regex("""Stornierungen:\s*-4,50 EUR""").containsMatchIn(nonZero), nonZero)
    }
}
