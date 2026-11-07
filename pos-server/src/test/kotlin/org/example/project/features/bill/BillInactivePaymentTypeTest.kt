package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.error.PosErrorCode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MD-06: a deactivated payment type takes no NEW money. The live QA pass booked a bill and rebooked
 * another onto a type deleted (soft, `is_active = false`) minutes earlier — the till's picker hid it,
 * the server did not. Historic bills keep their type: a storno of one still refunds through it.
 */
@DisplayName("A deactivated payment type takes no new payments")
class BillInactivePaymentTypeTest {
    private val deviceId = "md06-inactive-tender-device"
    private val fixture = RebookFixture(deviceId)

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun deactivate(paymentTypeId: Long) = transaction { PaymentType.findById(paymentTypeId)!!.isActive = false }

    private fun rebookTo(
        amount: String,
        paymentTypeId: Long,
    ) = BillDto.RebookRequestDto(
        payments = listOf(PaymentDto.PaymentRequest(amount = amount, paymentTypeId = paymentTypeId)),
        voucherRedeem = emptyList(),
        tips = "0.00",
    )

    @Test
    fun `a new bill with a deactivated type is refused with 409`() =
        testApp { _ ->
            val error =
                assertFailsWith<PosException> {
                    fixture.closeSale(SaleSpec(recipient = TipRecipient.EMPLOYEE, tender = Tender.CARD, tenderInactive = true))
                }

            assertEquals(PosErrorCode.PAYMENT_TYPE_INACTIVE, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
            transaction { assertTrue(Bill.all().empty(), "a refused bill must leave no row") }
        }

    @Test
    fun `a Zahlartwechsel onto a deactivated type is refused before the Storno`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE, payByCard = false)
            deactivate(sale.cardTypeId)

            val error = assertFailsWith<PosException> { fixture.rebook(sale, rebookTo("23.00", sale.cardTypeId)) }

            assertEquals(PosErrorCode.PAYMENT_TYPE_INACTIVE, error.code)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus)
            transaction {
                assertTrue(Bill.find { BillTable.deletedBillId eq sale.billId }.empty(), "the original must not be stornoed")
            }
        }

    @Test
    fun `a historic bill keeps its deactivated type and can still be stornoed`() =
        testApp { _ ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE, payByCard = true)
            deactivate(sale.cardTypeId)

            fixture.storno(sale)

            transaction {
                val original = assertNotNull(Bill.findById(sale.billId))
                assertEquals(
                    sale.cardTypeId,
                    original.payments
                        .single()
                        .paymentType.id.value,
                    "the original keeps its type",
                )
                assertTrue(!Bill.find { BillTable.deletedBillId eq sale.billId }.empty(), "the storno went through")
            }
        }
}
