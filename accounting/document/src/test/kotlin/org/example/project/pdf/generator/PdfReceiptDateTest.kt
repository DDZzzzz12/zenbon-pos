package org.example.project.pdf.generator

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * emu22 NOTE-hh: the PDF receipt header read „Datum: 2026-10-03T22:59:41.961638" — the raw wire
 * timestamp. It now renders the date the way the paper receipt does (`formatLocalDateTime`).
 */
class PdfReceiptDateTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    @Test
    fun `the PDF receipt date is German dd_MM_yyyy HH_mm, not the raw ISO timestamp`() {
        val bill =
            BillDto.BillResponseDto(
                billId = 1L,
                employeeId = "emp-1",
                employeeNumber = "K001",
                tipRecipient = "EMPLOYEE",
                tableSessionId = 1L,
                tableNumber = "T1",
                billNumber = "RE-261003-7",
                billAmount = "0.00",
                paidAmount = "0.00",
                tips = "0.00",
                startTime = "2026-10-03T22:50:00",
                endTime = "2026-10-03T22:59:41.961638",
                orderItems = emptyList(),
                payments = emptyList(),
            )
        val text = extractText(generateBillReceiptPdfBytes(bill, ShopHeaderData(shopName = "Test GmbH")))
        assertTrue(Regex("""Datum: 03\.10\.2026,? 22:59""").containsMatchIn(text), text)
        assertFalse("2026-10-03T" in text, "raw ISO timestamp on the receipt: $text")
    }
}
