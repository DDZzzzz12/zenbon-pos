package org.example.project.admin.features.staff

import org.example.project.admin.core.auth.canGrantRole
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The staff editor's role picker offers only roles the actor may grant — the server refuses any
 * role whose group confers a capability the actor does not hold (QA admin19 NOTE-i).
 */
class StaffRoleGrantGateTest {
    private fun policyOf(role: EmployeeRole) = SetAccessPolicy(CapabilityBundles.forRole(role))

    @Test
    fun managerIsNotOfferedAdministrator() {
        assertFalse(canGrantRole(policyOf(EmployeeRole.MANAGER), EmployeeRole.ADMIN))
    }

    @Test
    fun managerIsOfferedCashierAndManager() {
        assertTrue(canGrantRole(policyOf(EmployeeRole.MANAGER), EmployeeRole.CASHIER))
        assertTrue(canGrantRole(policyOf(EmployeeRole.MANAGER), EmployeeRole.MANAGER))
    }

    @Test
    fun adminIsOfferedEveryRole() {
        EmployeeRole.entries.forEach { assertTrue(canGrantRole(policyOf(EmployeeRole.ADMIN), it), it.name) }
    }

    @Test
    fun cashierCannotGrantManager() {
        assertFalse(canGrantRole(policyOf(EmployeeRole.CASHIER), EmployeeRole.MANAGER))
    }
}
