package org.example.project.admin.features.supplier

import org.example.project.admin.features.supplier.presentation.SupplierAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Lieferanten write controls follow `CREDITOR_WRITE`. */
class SupplierAccessTest {
    @Test
    fun holding_creditor_write_offers_the_write_controls() {
        assertEquals(SupplierAccess(mayWrite = true), SupplierAccess.of(SetAccessPolicy(setOf(Capability.CREDITOR_WRITE))))
    }

    @Test
    fun a_read_only_group_is_offered_none() {
        assertEquals(SupplierAccess(mayWrite = false), SupplierAccess.of(SetAccessPolicy(setOf(Capability.CREDITOR_READ))))
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(SupplierAccess(mayWrite = true), SupplierAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)))
        assertEquals(SupplierAccess(mayWrite = false), SupplierAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)))
    }
}
