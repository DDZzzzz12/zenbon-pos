package org.example.project.admin.features.debtors

import org.example.project.admin.features.debtors.presentation.DebtorsAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Debitoren write controls follow `DEBTOR_WRITE`. */
class DebtorsAccessTest {
    @Test
    fun holding_debtor_write_offers_the_write_controls() {
        assertEquals(DebtorsAccess(mayWrite = true), DebtorsAccess.of(SetAccessPolicy(setOf(Capability.DEBTOR_WRITE))))
    }

    @Test
    fun a_read_only_group_is_offered_none() {
        assertEquals(DebtorsAccess(mayWrite = false), DebtorsAccess.of(SetAccessPolicy(setOf(Capability.DEBTOR_READ))))
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(DebtorsAccess(mayWrite = true), DebtorsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)))
        assertEquals(DebtorsAccess(mayWrite = false), DebtorsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)))
    }
}
