package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceEditorState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SalesInvoiceEditorReadOnlyTest {
    @Test
    fun `a new or draft invoice is editable`() {
        assertFalse(SalesInvoiceEditorState(status = null).readOnly)
        assertFalse(SalesInvoiceEditorState(invoiceId = 7, status = "DRAFT").readOnly)
    }

    @Test
    fun `a loaded issued or cancelled invoice is read only`() {
        assertTrue(SalesInvoiceEditorState(invoiceId = 7, status = "ISSUED").readOnly)
        assertTrue(SalesInvoiceEditorState(invoiceId = 7, status = "CANCELLED").readOnly)
    }

    @Test
    fun `a status without a loaded invoice is not read only`() {
        // readOnly requires an id: a status alone (no invoice loaded) never freezes the form.
        assertFalse(SalesInvoiceEditorState(status = "ISSUED").readOnly)
    }
}
