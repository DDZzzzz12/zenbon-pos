package org.example.project.features.salesinvoice

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.BillInvoiceabilityFacts
import org.example.project.features.salesinvoice.domain.service.BillInvoiceabilityGuard
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BillInvoiceabilityGuardTest {
    private fun facts(
        kind: BillKind = BillKind.SALE,
        isCancelled: Boolean = false,
        isTraining: Boolean = false,
        billAmount: String = "37.8900",
        vatDetailTotal: String = "37.8900",
        hasLiveInvoice: Boolean = false,
        ownerTip: String = "0.0000",
        receivableTender: String = "0.0000",
        promotionAmount: String = "0.0000",
    ) = BillInvoiceabilityFacts(
        billId = 1L,
        kind = kind,
        isCancelled = isCancelled,
        isTraining = isTraining,
        billAmount = BigDecimal(billAmount),
        promotionAmount = BigDecimal(promotionAmount),
        vatDetailTotal = BigDecimal(vatDetailTotal),
        ownerTip = BigDecimal(ownerTip),
        hasLiveInvoice = hasLiveInvoice,
        receivableTender = BigDecimal(receivableTender),
    )

    @Test
    fun `a settled, live, self-consistent bill is invoiceable`() {
        BillInvoiceabilityGuard.requireInvoiceable(facts(), invoiceGross = BigDecimal("37.8900"))
        Unit
    }

    @Test
    fun `a bill that is not a standing sale is refused with 409 BILL_NOT_A_STANDING_SALE`() {
        val refused =
            listOf(
                facts(kind = BillKind.STORNO) to "Storno bill",
                facts(isCancelled = true) to "cancelled original",
                facts(kind = BillKind.RECEIVABLE_CLEARING) to "receivable clearing",
            )
        refused.forEach { (facts, what) ->
            val error =
                assertFailsWith<PosException>(what) {
                    BillInvoiceabilityGuard.requireInvoiceable(facts, BigDecimal("37.8900"))
                }
            assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE, error.code, what)
            assertEquals(HttpStatusCode.Conflict, error.httpStatus, what)
        }
    }

    @Test
    fun `the standing-sale refusal wins over every amount check`() {
        // A receivable clearing carries no VAT rows: with zero amounts it would pass the amount checks.
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(
                    facts(kind = BillKind.RECEIVABLE_CLEARING, billAmount = "0.0000", vatDetailTotal = "0.0000"),
                    invoiceGross = BigDecimal.ZERO,
                )
            }
        assertEquals(PosErrorCode.BILL_NOT_A_STANDING_SALE, error.code)
        Unit
    }

    @Test
    fun `a training bill is refused`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(facts(isTraining = true), BigDecimal("37.8900"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        Unit
    }

    @Test
    fun `a bill whose own VAT details disagree with its total is refused`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(
                    facts(billAmount = "37.8900", vatDetailTotal = "37.8800"),
                    BigDecimal("37.8900"),
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        Unit
    }

    @Test
    fun `a bill that already has a live invoice is refused`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(facts(hasLiveInvoice = true), BigDecimal("37.8900"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_ALREADY_INVOICED, error.code)
        Unit
    }

    @Test
    fun `an invoice total one cent off the bill is refused`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(facts(), invoiceGross = BigDecimal("37.9000"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH, error.code)
        Unit
    }

    @Test
    fun `scale differences are not treated as a mismatch`() {
        BillInvoiceabilityGuard.requireInvoiceable(
            facts(billAmount = "37.89", vatDetailTotal = "37.8900"),
            invoiceGross = BigDecimal("37.890000"),
        )
        Unit
    }

    @Test
    fun `an owner tip in the VAT details is not an inconsistency - the invoice is the meal without it`() {
        // Meal 37.89 + owner tip 2.00 folded into the VAT rows = 39.89.
        BillInvoiceabilityGuard.requireInvoiceable(
            facts(billAmount = "37.8900", vatDetailTotal = "39.8900", ownerTip = "2.00"),
            invoiceGross = BigDecimal("37.8900"),
        )
        Unit
    }

    @Test
    fun `an invoice that carries the owner tip is refused and says the tip is never part of it`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(
                    facts(billAmount = "37.8900", vatDetailTotal = "39.8900", ownerTip = "2.00"),
                    invoiceGross = BigDecimal("39.8900"),
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_AMOUNT_MISMATCH, error.code)
        assertTrue(error.message!!.contains("without its owner tip of 2.00"), error.message)
        Unit
    }

    @Test
    fun `an owner-tip bill that disagrees with itself is still refused, naming the tip it took out`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(
                    facts(billAmount = "37.8900", vatDetailTotal = "39.8800", ownerTip = "2.00"),
                    invoiceGross = BigDecimal("37.8900"),
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        assertTrue(
            error.message!!.contains("VAT details 39.8800 less the owner tip 2.00 (= 37.8800)"),
            "the refusal must say which figures disagree: ${error.message}",
        )
        Unit
    }

    @Test
    fun `a receivable tender above the meal means the tip is on account - refused`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(facts(receivableTender = "39.8900"), BigDecimal("37.8900"))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_TIP_ON_ACCOUNT, error.code)
        assertEquals(HttpStatusCode.Conflict, error.httpStatus)
        Unit
    }

    @Test
    fun `a receivable tender equal to the meal is fine`() {
        BillInvoiceabilityGuard.requireInvoiceable(facts(receivableTender = "37.8900"), BigDecimal("37.8900"))
        Unit
    }

    @Test
    fun `a promotion already off the VAT details is not an inconsistency - the invoice is the meal after it`() {
        BillInvoiceabilityGuard.requireInvoiceable(
            facts(billAmount = "40.0000", promotionAmount = "2.1100", vatDetailTotal = "37.8900"),
            invoiceGross = BigDecimal("37.8900"),
        )
        Unit
    }

    @Test
    fun `a promoted bill that disagrees with itself is refused, naming the promotions`() {
        val error =
            assertFailsWith<PosException> {
                BillInvoiceabilityGuard.requireInvoiceable(
                    facts(billAmount = "40.0000", promotionAmount = "2.0000", vatDetailTotal = "37.8900"),
                    invoiceGross = BigDecimal("37.8900"),
                )
            }
        assertEquals(PosErrorCode.SALES_INVOICE_BILL_NOT_INVOICEABLE, error.code)
        assertTrue(error.message!!.contains("less promotions 2.0000 (= 38.0000)"), error.message)
        Unit
    }
}
