package org.example.project.admin.features.invoices

import org.example.project.admin.features.invoices.presentation.InvoicesAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Rechnungen row actions follow the capabilities the server's rules require. */
class InvoicesAccessTest {
    @Test
    fun a_read_only_group_is_offered_neither_capture_nor_review() {
        val access = InvoicesAccess.of(SetAccessPolicy(setOf(Capability.INVOICE_READ, Capability.DOCUMENT_READ)))

        assertEquals(InvoicesAccess(mayCapture = false, mayEditInvoice = false, mayMarkReviewed = false), access)
    }

    @Test
    fun capture_needs_both_the_document_and_the_invoice_write() {
        assertEquals(
            InvoicesAccess(mayCapture = false, mayEditInvoice = true, mayMarkReviewed = true),
            InvoicesAccess.of(SetAccessPolicy(setOf(Capability.INVOICE_WRITE))),
        )
        assertEquals(
            InvoicesAccess(mayCapture = false, mayEditInvoice = false, mayMarkReviewed = false),
            InvoicesAccess.of(SetAccessPolicy(setOf(Capability.DOCUMENT_WRITE))),
        )
        assertEquals(
            InvoicesAccess(mayCapture = true, mayEditInvoice = true, mayMarkReviewed = true),
            InvoicesAccess.of(SetAccessPolicy(setOf(Capability.DOCUMENT_WRITE, Capability.INVOICE_WRITE))),
        )
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(
            InvoicesAccess(mayCapture = true, mayEditInvoice = true, mayMarkReviewed = true),
            InvoicesAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)),
        )
        assertEquals(
            InvoicesAccess(mayCapture = false, mayEditInvoice = false, mayMarkReviewed = false),
            InvoicesAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)),
        )
    }
}
