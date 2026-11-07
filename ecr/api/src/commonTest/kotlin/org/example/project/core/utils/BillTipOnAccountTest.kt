package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.money.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The till half of the tip-on-account guard: receivable tender above the meal hides the invoice action. */
class BillTipOnAccountTest {
    private fun payment(
        amount: String,
        onAccount: Boolean,
        status: PaymentStatus = PaymentStatus.COMPLETED,
    ) = PaymentDto.PaymentResponse(
        id = 1L,
        amount = amount,
        paymentTypeId = 1L,
        paymentTypeName = "type",
        billId = 7L,
        createdAt = "2026-09-30T12:10:00",
        paymentStatus = status,
        createsReceivable = onAccount,
    )

    private fun bill(
        payments: List<PaymentDto.PaymentResponse>,
        promotionAmount: String = "0.00",
    ) = BillDto.BillResponseDto(
        billId = 7L,
        employeeId = "emp-1",
        employeeNumber = "07",
        tipRecipient = "OWNER",
        tableSessionId = 500L,
        tableNumber = "12",
        billNumber = "RE-7",
        billAmount = "23.00",
        promotionAmount = promotionAmount,
        paidAmount = "25.00",
        tips = "2.00",
        startTime = "2026-09-30T12:00:00",
        endTime = "2026-09-30T12:10:00",
        orderItems = emptyList(),
        payments = payments,
    )

    @Test
    fun a_tip_paid_on_account_is_detected() {
        assertTrue(bill(listOf(payment("25.00", onAccount = true))).isTipOnAccount())
    }

    @Test
    fun the_meal_on_account_with_the_tip_in_cash_is_fine() {
        val bill = bill(listOf(payment("23.00", onAccount = true), payment("2.00", onAccount = false)))
        assertFalse(bill.isTipOnAccount())
        assertEquals(0, bill.receivableTender().compareTo(Decimal.parse("23.00")))
    }

    @Test
    fun a_refunded_leg_on_account_does_not_count() {
        assertFalse(bill(listOf(payment("25.00", onAccount = true, status = PaymentStatus.REFUNDED))).isTipOnAccount())
    }

    @Test
    fun the_meal_is_after_promotions() {
        // Meal 23.00 - 3.00 promotion = 20.00; 21.00 on account means 1.00 of the tip is on account.
        val bill = bill(listOf(payment("21.00", onAccount = true), payment("1.00", onAccount = false)), promotionAmount = "3.00")
        assertTrue(bill.isTipOnAccount())
    }
}
