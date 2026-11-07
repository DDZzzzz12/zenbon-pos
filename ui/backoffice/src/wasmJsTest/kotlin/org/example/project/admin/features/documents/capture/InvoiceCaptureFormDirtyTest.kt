package org.example.project.admin.features.documents.capture

import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureState
import org.example.project.admin.features.documents.presentation.capture.VatRowForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The explicit, field-by-field dirty comparison and its three scopes. */
class InvoiceCaptureFormDirtyTest {
    private val base =
        InvoiceCaptureForm(
            invoiceNumber = "RE-1",
            issueDate = "2026-03-01",
            grossTotal = "238.00",
            sellerName = "Lieferant GmbH",
            vatRows = listOf(VatRowForm(rate = "19.00", gross = "", description = "")),
        )

    @Test
    fun equalFormsAreTheSame_evenAsSeparateInstances() {
        val twin = base.copy(vatRows = listOf(VatRowForm(rate = "19.00")))
        assertTrue(base.sameAs(twin))
        assertTrue(base.sameAs(base))
        assertTrue(base.contentEquals(twin))
        assertTrue(base.bookingEquals(twin))
    }

    @Test
    fun everySingleContentFieldMakesTheFormDifferent() {
        listOf(
            base.copy(invoiceNumber = "RE-2"),
            base.copy(issueDate = "2026-03-02"),
            base.copy(grossTotal = "239.00"),
            base.copy(sellerName = "Anderer"),
            base.copy(invoiceTypeCode = "381"),
            base.copy(precedingInvoiceReference = "RE-0"),
        ).forEach {
            assertFalse(base.sameAs(it), it.toString())
            assertFalse(base.contentEquals(it), it.toString())
            assertTrue(base.bookingEquals(it), "a content edit leaves booking alone")
        }
    }

    @Test
    fun vatRowsDifferBySizeAndByEachRowField() {
        assertFalse(base.sameAs(base.copy(vatRows = base.vatRows + VatRowForm(rate = "7.00"))))
        assertFalse(base.sameAs(base.copy(vatRows = listOf(VatRowForm(rate = "7.00")))))
        assertFalse(base.sameAs(base.copy(vatRows = listOf(VatRowForm(rate = "19.00", gross = "1.00")))))
        assertFalse(base.sameAs(base.copy(vatRows = listOf(VatRowForm(rate = "19.00", description = "Speisen")))))
    }

    @Test
    fun aBookingOnlyChangeIsDirtyButNotContentDirty() {
        val edited = base.copy(bookingText = "Wareneinkauf")
        assertFalse(base.sameAs(edited))
        assertTrue(base.contentEquals(edited))
        assertFalse(base.bookingEquals(edited))
        listOf(base.copy(bpAccountNo = "70001"), base.copy(accountNo = "3400")).forEach { assertFalse(base.bookingEquals(it)) }
    }

    @Test
    fun theStateScopesFollowTheComparison() {
        val clean = InvoiceCaptureState(form = base, pristine = base)
        assertFalse(clean.isDirty)
        assertFalse(clean.contentDirty)
        assertFalse(clean.bookingDirty)

        val bookingOnly = InvoiceCaptureState(form = base.copy(bookingText = "x"), pristine = base)
        assertTrue(bookingOnly.isDirty)
        assertFalse(bookingOnly.contentDirty)
        assertTrue(bookingOnly.bookingDirty)

        val contentOnly = InvoiceCaptureState(form = base.copy(sellerName = "y"), pristine = base)
        assertEquals(true to false, contentOnly.contentDirty to contentOnly.bookingDirty)
    }
}
