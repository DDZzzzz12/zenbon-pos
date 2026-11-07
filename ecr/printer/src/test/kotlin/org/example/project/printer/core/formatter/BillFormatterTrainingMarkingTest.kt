package org.example.project.printer.core.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.setup.ShopHeaderData
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.utils.formatter.ReceiptSigns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * emu20 FAIL-C: a Schulungsbeleg printed exactly like a sale — only the QR's process data said
 * `AVTraining`. It must be unmistakable on paper: marked at the head (above the Beleg number) and
 * again at the foot, also when it is a Storno of a training bill.
 */
class BillFormatterTrainingMarkingTest {
    private fun bill(
        isTraining: Boolean,
        storno: Boolean = false,
    ) = BillDto.BillResponseDto(
        billId = 1,
        employeeId = "emp-1",
        employeeNumber = "K002",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 1,
        tableNumber = "T1",
        billNumber = if (storno) "RE-261003-47" else "RE-261003-46",
        billAmount = if (storno) "-7.40" else "7.40",
        paidAmount = if (storno) "-7.40" else "7.40",
        tips = "0.00",
        startTime = "2026-10-03T12:00:00",
        endTime = "2026-10-03T12:05:00",
        deletedBillId = if (storno) "RE-261003-46" else null,
        orderItems = emptyList(),
        payments =
            listOf(
                PaymentDto.PaymentResponse(
                    id = 1,
                    amount = "7.40",
                    paymentTypeId = 1,
                    paymentTypeName = "BAR",
                    billId = 1,
                    createdAt = "2026-10-03T12:05:00",
                    paymentStatus = if (storno) PaymentStatus.REFUNDED else PaymentStatus.COMPLETED,
                ),
            ),
        relations = if (storno) BillDto.BillRelationsDto(kind = BillKind.STORNO) else BillDto.BillRelationsDto(),
        isTraining = isTraining,
    )

    private fun print(bill: BillDto.BillResponseDto) =
        runBlocking { BillFormatter().format(bill, ShopHeaderData(shopName = "Test"), Language.GERMAN) }.printedLines()

    private fun List<String>.indicesWith(text: String) = indices.filter { text in this[it] }

    @Test
    fun `a training bill is marked at the head and at the foot`() {
        val lines = print(bill(isTraining = true))

        val titles = lines.indicesWith(ReceiptSigns.TRAINING_TITLE)
        val notices = lines.indicesWith(ReceiptSigns.TRAINING_NOTICE)
        assertEquals(2, titles.size, "title at head and foot:\n${lines.joinToString("\n")}")
        assertEquals(2, notices.size, "notice at head and foot:\n${lines.joinToString("\n")}")

        val number = lines.indexOfFirst { "RE-261003-46" in it }
        val total = lines.indexOfFirst { "GESAMT" in it }
        assertTrue(titles.first() < number, "the head marking stands above the Beleg number")
        assertTrue(titles.last() > total, "the foot marking stands below the total")
    }

    @Test
    fun `a Storno of a training bill is marked too`() {
        val lines = print(bill(isTraining = true, storno = true))

        assertEquals(2, lines.indicesWith(ReceiptSigns.TRAINING_TITLE).size, lines.joinToString("\n"))
        assertTrue(lines.any { it.trimEnd().endsWith("STORNO") }, "still headed as a Storno")
    }

    @Test
    fun `a real sale carries no training marking`() {
        val lines = print(bill(isTraining = false))

        assertTrue(lines.none { ReceiptSigns.TRAINING_TITLE in it || "SCHULUNG" in it }, lines.joinToString("\n"))
    }
}
