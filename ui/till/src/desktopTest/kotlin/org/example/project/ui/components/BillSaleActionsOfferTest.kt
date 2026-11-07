package org.example.project.ui.components

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bill "Aktionen" dialog offers *Rechnung erstellen* and *Bewirtungsbeleg* only for a standing
 * sale — never for a Zahlartwechsel correction, a Storno bill, a cancelled original or a receivable
 * clearing. Derived from the bill's relations, never from a role. The UI half of CLAUDE.md rule 8;
 * the server half is `SalesInvoiceStandingSaleTest` and `BillCorrectionReceiptPrintTest`, which
 * refuse the same bills with `BILL_NOT_A_STANDING_SALE`.
 */
class BillSaleActionsOfferTest {
    private fun bill(
        kind: BillKind = BillKind.SALE,
        isCancelled: Boolean = false,
        deletingReason: String? = null,
        deletedBillId: String? = null,
        salesInvoiceId: Long? = null,
    ) = BillDto.BillResponseDto(
        billId = 5,
        employeeId = "emp-1",
        employeeNumber = "07",
        tipRecipient = "EMPLOYEE",
        tableSessionId = 500L,
        tableNumber = "12",
        billNumber = "RE-5",
        billAmount = "23.00",
        paidAmount = "23.00",
        tips = "0.00",
        startTime = "2026-09-28T12:00:00",
        endTime = "2026-09-28T12:10:00",
        deletedBillId = deletedBillId,
        deletingReason = deletingReason,
        orderItems = emptyList(),
        payments = emptyList(),
        salesInvoiceId = salesInvoiceId,
        relations = BillDto.BillRelationsDto(kind = kind, isCancelled = isCancelled),
    )

    private val notASale =
        mapOf(
            "Storno bill" to bill(kind = BillKind.STORNO, deletedBillId = "4", deletingReason = "Gast"),
            "cancelled original" to bill(isCancelled = true, deletingReason = "Gast"),
            "receivable clearing" to bill(kind = BillKind.RECEIVABLE_CLEARING),
        )

    @Test
    fun `a standing sale offers the invoice and the Bewirtungsbeleg`() {
        assertTrue(createInvoiceOffered(bill()))
        assertTrue(bewirtungsbelegOffered(bill()))
    }

    @Test
    fun `a correction, a Storno, a cancelled original and a clearing offer neither`() {
        notASale.forEach { (what, bill) ->
            assertFalse(createInvoiceOffered(bill), "$what must not offer Rechnung erstellen")
            assertFalse(bewirtungsbelegOffered(bill), "$what must not offer Bewirtungsbeleg")
        }
    }

    @Test
    fun `a stornoed bill from a server that sends no relations is still not offered`() {
        // Relations default to an ordinary sale; the raw Storno columns still say it no longer stands.
        val legacyStorno = bill(deletedBillId = "4", deletingReason = "Gast")
        val legacyCancelled = bill(deletingReason = "Gast")
        listOf(legacyStorno, legacyCancelled).forEach {
            assertFalse(createInvoiceOffered(it))
            assertFalse(bewirtungsbelegOffered(it))
        }
    }

    @Test
    fun `a sale that already carries an invoice offers no second one, but keeps its Bewirtungsbeleg`() {
        val invoiced = bill(salesInvoiceId = 7L)
        assertFalse(createInvoiceOffered(invoiced))
        assertTrue(bewirtungsbelegOffered(invoiced))
    }

    @Test
    fun `the server refusal reads as a localized sentence, not the English detail`() {
        val error =
            PosApiException(
                code = PosErrorCode.BILL_NOT_A_STANDING_SALE,
                httpStatus = 409,
                message = "Bill 5 cannot be invoiced: it is a Zahlartwechsel correction Beleg",
                traceId = "t-1",
            ).toUiError()
        assertEquals(SalesInvoiceTillKeys.notStandingSaleTitle, error.titleKey)
        assertEquals(SalesInvoiceTillKeys.notStandingSaleMessage, error.messageKey)
    }

    @Test
    fun `a just-billed sale keeps the created title, a Neubuchung confirms the Zahlartwechsel, a Storno or cancelled bill reads neutral`() {
        assertEquals(BillReceiptKeys.successMessage, billActionsTitleKey(bill(), created = true), "ordinary billing text unchanged")
        assertEquals(BillReceiptKeys.successMessage, billActionsTitleKey(bill(kind = BillKind.RECEIVABLE_CLEARING), created = true))
        // emu21 NOTE-z: only the dialog the rebook itself opens confirms it; Aktionen later reads Beleg.
        assertEquals(BillReceiptKeys.rebookSuccess, billActionsTitleKey(bill(kind = BillKind.REBOOKING), created = true))
        assertEquals(BillReceiptKeys.receiptTitle, billActionsTitleKey(bill(kind = BillKind.REBOOKING), created = false))
        listOf(true, false).forEach { created ->
            listOf(
                bill(kind = BillKind.STORNO, deletedBillId = "4", deletingReason = "Gast"),
                bill(isCancelled = true, deletingReason = "Gast"),
                bill(kind = BillKind.REBOOKING, isCancelled = true, deletingReason = "Gast"),
                bill(deletedBillId = "4", deletingReason = "Gast"),
            ).forEach { assertEquals(BillReceiptKeys.receiptTitle, billActionsTitleKey(it, created), "no 'created' for $it") }
        }
    }

    @Test
    fun `a sale opened again from Berichte reads Beleg, not Rechnung erfolgreich erstellt`() {
        assertEquals(BillReceiptKeys.receiptTitle, billActionsTitleKey(bill(), created = false))
        assertEquals(BillReceiptKeys.receiptTitle, billActionsTitleKey(bill(kind = BillKind.RECEIVABLE_CLEARING), created = false))
    }

    @Test
    fun `a disabled Rechnung erstellen names its reason, the missing capability first`() {
        // QA emu14 NOTE-3: a cashier saw the button disabled with no reason at all.
        assertEquals(SalesInvoiceTillKeys.createInvoiceNeedsCapability, createInvoiceBlockedReason(isManager = false, tipOnAccount = false))
        assertEquals(SalesInvoiceTillKeys.createInvoiceNeedsCapability, createInvoiceBlockedReason(isManager = false, tipOnAccount = true))
        assertEquals(SalesInvoiceTillKeys.tipOnAccountMessage, createInvoiceBlockedReason(isManager = true, tipOnAccount = true))
        assertEquals(null, createInvoiceBlockedReason(isManager = true, tipOnAccount = false))
    }
}
