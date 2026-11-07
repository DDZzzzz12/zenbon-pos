package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.dto.tse.TseReceiptDataDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.printer.core.enums.CodePage
import org.example.project.utils.formatter.TseReceiptText
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F63: a German shop's paper Beleg printed `BAR 11.50 EUR`, `SUMME: 6.00 EUR` and a VAT row of
 * `0.39 5.61 6.00`. Every human-readable amount now prints with a decimal comma; the TSE block is
 * not an amount and stays exactly as the TSE returned it (AEAO zu § 146a AO Nr. 2.4.4).
 */
class BillFormatterDecimalCommaTest {
    /**
     * TSE values deliberately carry dot-decimals (`.000Z` times, a client serial ending `11.50`), so
     * a formatter that "Germanised" the TSE block would be caught here.
     */
    private val tse =
        TseReceiptDataDto(
            transactionNumber = 4711L,
            startTime = "2026-05-05T01:02:03.000Z",
            endTime = "2026-05-05T01:02:04.000Z",
            signatureCounter = 9131L,
            tseSerialNumber = "8C6B9B4D4A0C6FBA2E7C9F0D1E2A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C",
            clientSerialNumber = "KASSE-11.50",
            signature = "MEUCIQD0abc/+xyz==",
        )

    private val bill =
        BillDto.BillResponseDto(
            billId = 1,
            employeeId = "emp-1",
            employeeNumber = "K001",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 1,
            tableNumber = "T1",
            billNumber = "RE-261006-1",
            billAmount = "11.5000",
            paidAmount = "11.50",
            tips = "0.00",
            startTime = "2026-10-06T12:00:00",
            endTime = "2026-10-06T12:05:00",
            orderItems = emptyList(),
            payments =
                listOf(
                    PaymentDto.PaymentResponse(
                        id = 1,
                        amount = "11.50",
                        paymentTypeId = 1,
                        paymentTypeName = "BAR",
                        billId = 1,
                        createdAt = "2026-10-06T12:05:00",
                        paymentStatus = PaymentStatus.COMPLETED,
                    ),
                ),
            vatDetails =
                listOf(
                    BillDto.BillVatDetailDto(id = 1, vatId = "B", vatRate = 0.07, vatAmount = "0.39", netAmount = "5.61", totalAmount = "6.00"),
                    BillDto.BillVatDetailDto(id = 2, vatId = "A", vatRate = 0.19, vatAmount = "0.88", netAmount = "4.62", totalAmount = "5.50"),
                ),
            tseTransactionId = 99L,
            tseReceiptData = tse,
        )

    private fun print(): List<String> =
        runBlocking { BillFormatter().format(bill, ShopHeaderData(shopName = "Test"), Language.GERMAN) }
            .printedLines()
            .map { it.trimEnd() }

    /** The printed lines with the TSE block cut out, and the TSE block itself. */
    private fun split(lines: List<String>): Pair<List<String>, List<String>> {
        val expected = TseReceiptText.lines(tse, BaseDocumentFormatter.MAX_CHARS_SMALL)
        val start = lines.indexOfFirst { it.endsWith(expected.first()) }
        assertTrue(start >= 0, "no TSE block on the Beleg:\n${lines.joinToString("\n")}")
        val block = lines.subList(start, start + expected.size)
        return (lines.subList(0, start) + lines.subList(start + expected.size, lines.size)) to block
    }

    @Test
    fun `the totals print with a decimal comma and never with a dot`() {
        val (amounts, _) = split(print())
        val text = amounts.joinToString("\n")

        assertTrue(amounts.any { "BAR" in it && "11,50 EUR" in it }, text)
        assertTrue(amounts.any { "SUMME" in it && "11,50 EUR" in it }, text)
        assertTrue(amounts.any { "GESAMT" in it && "11,50 EUR" in it }, text)
        assertFalse("11.50" in text, "no dot-decimal amount outside the TSE block:\n$text")
        assertFalse(Regex("""\d\.\d{2}(?![\d.])""").containsMatchIn(text), "no dot-decimal amount anywhere (dates excepted):\n$text")
    }

    @Test
    fun `the VAT table prints its rate as "7 %" and its values with a decimal comma`() {
        val (amounts, _) = split(print())

        val row = amounts.first { it.trimStart().startsWith("B 7 %") }
        assertEquals(listOf("B", "7", "%", "0,39", "5,61", "6,00"), row.trim().split(Regex(" +")), "'$row'")
        assertTrue(amounts.any { it.trimStart().startsWith("A 19 %") }, amounts.joinToString("\n"))
    }

    @Test
    fun `the TSE block is printed exactly as the TSE returned it`() {
        val (_, block) = split(print())

        val expected = TseReceiptText.lines(tse, BaseDocumentFormatter.MAX_CHARS_SMALL)

        // The first line may carry the argument byte of the preceding ESC command; the text itself
        // must match. Every following line must match byte for byte.
        assertTrue(block.first().endsWith(expected.first()), "'${block.first()}'")
        assertEquals(expected.drop(1), block.drop(1))
        assertTrue(block.any { it.endsWith("KASSE-11.50") }, "a TSE value keeps its dot: $block")
        assertTrue(block.any { it.endsWith("2026-05-05T01:02:03.000Z") }, "TSE times keep their dot: $block")
    }

    @Test
    fun `the comma does not depend on the JVM default locale`() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val (amounts, _) = split(print())
            assertTrue(amounts.any { "BAR" in it && "11,50 EUR" in it }, amounts.joinToString("\n"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the currency suffix stays EUR on CP850, which has no euro sign`() {
        assertEquals("EUR", CodePage.CP850.currencySuffix)
        assertEquals("EUR", CodePage.GB18030.currencySuffix)
        assertEquals("€", CodePage.CP858.currencySuffix)
    }
}
