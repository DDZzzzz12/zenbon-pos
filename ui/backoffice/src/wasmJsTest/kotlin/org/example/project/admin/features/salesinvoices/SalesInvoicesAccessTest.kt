package org.example.project.admin.features.salesinvoices

import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Ausgangsrechnungen write controls follow `INVOICE_WRITE`. */
class SalesInvoicesAccessTest {
    @Test
    fun holding_invoice_write_offers_the_write_controls() {
        assertEquals(SalesInvoicesAccess(mayWrite = true), SalesInvoicesAccess.of(SetAccessPolicy(setOf(Capability.INVOICE_WRITE))))
    }

    @Test
    fun a_read_only_group_is_offered_none() {
        assertEquals(SalesInvoicesAccess(mayWrite = false), SalesInvoicesAccess.of(SetAccessPolicy(setOf(Capability.INVOICE_READ))))
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(SalesInvoicesAccess(mayWrite = true), SalesInvoicesAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)))
        assertEquals(SalesInvoicesAccess(mayWrite = false), SalesInvoicesAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)))
    }
}
