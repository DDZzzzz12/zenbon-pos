package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** A bill on screen read as the inputs of a Zahlartwechsel: its billable total and its standing tender. */
class BillRebookTenderTest {
    private fun d(text: String) = Decimal.parse(text)

    private fun payment(
        typeId: Long,
        amount: String,
        status: PaymentStatus = PaymentStatus.COMPLETED,
    ) = PaymentDto.PaymentResponse(
        id = typeId,
        amount = amount,
        paymentTypeId = typeId,
        paymentTypeName = "type-$typeId",
        billId = 7L,
        createdAt = "2026-09-29T12:10:00",
        paymentStatus = status,
    )

    private fun bill(
        billAmount: String = "30.00",
        promotionAmount: String = "0.00",
        tips: String = "0.00",
        payments: List<PaymentDto.PaymentResponse> = emptyList(),
        vouchers: List<String> = emptyList(),
    ) = BillDto.BillResponseDto(
        billId = 7L,
        employeeId = "emp-1",
        employeeNumber = "07",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 500L,
        tableNumber = "12",
        billNumber = "RE-7",
        billAmount = billAmount,
        promotionAmount = promotionAmount,
        paidAmount = billAmount,
        tips = tips,
        startTime = "2026-09-29T12:00:00",
        endTime = "2026-09-29T12:10:00",
        voucherRedeem =
            vouchers.map {
                VoucherDto.RedeemVoucherResponse(
                    id = 1L,
                    voucherNumber = it,
                    voucherSource = VoucherSourceType.INTERNAL,
                    redeemedAmount = "5.00",
                    remainingValue = "0.00",
                    redeemedAt = "2026-09-29T12:10:00",
                    billId = 7L,
                )
            },
        orderItems = emptyList(),
        payments = payments,
    )

    @Test
    fun theTotalToRebookIsTheBillAmountLessItsPromotions() {
        assertEquals(d("25.00"), bill(billAmount = "30.0000", promotionAmount = "5.0000").rebookBillTotal())
        assertEquals(d("30.00"), bill().rebookBillTotal())
    }

    @Test
    fun theStandingTenderIsTheCompletedPaymentsTheTipAndTheVoucherNumbers() {
        val tender =
            bill(
                tips = "2.5000",
                payments = listOf(payment(1L, "20.0000"), payment(3L, "12.5000"), payment(2L, "9.00", PaymentStatus.REFUNDED)),
                vouchers = listOf("G-A"),
            ).rebookTender()

        assertEquals(listOf(1L to d("20.00"), 3L to d("12.50")), tender.payments, "a refunded payment is not standing")
        assertEquals(d("2.50"), tender.tip)
        assertEquals(listOf("G-A"), tender.vouchers)
    }

    @Test
    fun anUnreadableAmountCountsAsZero() {
        val tender = bill(tips = "", payments = listOf(payment(1L, "n/a"))).rebookTender()
        assertEquals(Decimal.ZERO, tender.tip)
        assertEquals(listOf(1L to Decimal.ZERO), tender.payments)
    }
}
