package org.example.project.pdf.generator

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.utils.formatter.ReceiptSigns
import org.openpdf.text.pdf.PdfReader
import org.openpdf.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * emu20 FAIL-C, PDF leg: a Schulungsbeleg rendered by an IPP printer set to PDF must carry the same
 * TRAININGSBELEG marking as the ESC/POS paper — head and foot.
 */
class PdfReceiptTrainingMarkingTest {
    private fun extractText(pdf: ByteArray): String {
        val reader = PdfReader(pdf)
        return try {
            (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    private fun bill(isTraining: Boolean) =
        BillDto.BillResponseDto(
            billId = 1L,
            employeeId = "emp-1",
            employeeNumber = "K002",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 1L,
            tableNumber = "T1",
            billNumber = "RE-261003-46",
            billAmount = "7.40",
            paidAmount = "7.40",
            tips = "0.00",
            startTime = "2026-10-03T12:00:00",
            endTime = "2026-10-03T12:05:00",
            orderItems = emptyList(),
            payments = emptyList(),
            isTraining = isTraining,
        )

    private val shopHeader = ShopHeaderData(shopName = "Test GmbH")

    @Test
    fun `a training bill's PDF is marked at the head and the foot`() {
        val text = extractText(generateBillReceiptPdfBytes(bill(isTraining = true), shopHeader))

        assertEquals(2, Regex(ReceiptSigns.TRAINING_TITLE).findAll(text).count(), text)
        assertEquals(2, Regex(Regex.escape(ReceiptSigns.TRAINING_NOTICE)).findAll(text).count(), text)
    }

    @Test
    fun `a real sale's PDF carries no training marking`() {
        val text = extractText(generateBillReceiptPdfBytes(bill(isTraining = false), shopHeader))

        assertFalse(ReceiptSigns.TRAINING_TITLE in text, text)
    }
}
