package org.example.project.admin.features.documents

import org.example.project.admin.features.documents.presentation.DocumentsAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Belege write controls follow the capabilities the server's rules require. */
class DocumentsAccessTest {
    @Test
    fun a_read_only_group_is_offered_no_write_action() {
        val access = DocumentsAccess.of(SetAccessPolicy(setOf(Capability.DOCUMENT_READ, Capability.INVOICE_READ)))

        assertEquals(DocumentsAccess(mayWrite = false, mayDelete = false, mayCancelInvoice = false), access)
    }

    @Test
    fun each_flag_follows_its_own_route_capability() {
        assertEquals(
            DocumentsAccess(mayWrite = true, mayDelete = false, mayCancelInvoice = false),
            DocumentsAccess.of(SetAccessPolicy(setOf(Capability.DOCUMENT_WRITE))),
        )
        assertEquals(
            DocumentsAccess(mayWrite = false, mayDelete = true, mayCancelInvoice = false),
            DocumentsAccess.of(SetAccessPolicy(setOf(Capability.DOCUMENT_DELETE))),
        )
        assertEquals(
            DocumentsAccess(mayWrite = false, mayDelete = false, mayCancelInvoice = true),
            DocumentsAccess.of(SetAccessPolicy(setOf(Capability.INVOICE_WRITE))),
        )
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(
            DocumentsAccess(mayWrite = true, mayDelete = true, mayCancelInvoice = true),
            DocumentsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)),
        )
        assertEquals(
            DocumentsAccess(mayWrite = false, mayDelete = false, mayCancelInvoice = false),
            DocumentsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)),
        )
    }
}
