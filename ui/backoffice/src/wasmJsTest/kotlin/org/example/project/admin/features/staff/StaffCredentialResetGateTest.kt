package org.example.project.admin.features.staff

import org.example.project.admin.core.auth.canResetStaffCredentials
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reset-PIN / reset-password buttons follow `EMPLOYEE_CREDENTIAL_RESET`, the capability the server's
 * `EmployeeRules` names for both routes, and not `EMPLOYEE_WRITE`.
 */
class StaffCredentialResetGateTest {
    @Test
    fun holding_the_reset_capability_offers_the_buttons() {
        assertTrue(canResetStaffCredentials(SetAccessPolicy(setOf(Capability.EMPLOYEE_CREDENTIAL_RESET))))
    }

    @Test
    fun editing_staff_records_alone_does_not() {
        assertFalse(canResetStaffCredentials(SetAccessPolicy(setOf(Capability.EMPLOYEE_WRITE, Capability.EMPLOYEE_READ))))
    }

    @Test
    fun the_auditor_cannot_reset_credentials() {
        val auditor = requireNotNull(CapabilityBundles.forGroup("AUDITOR")) { "no AUDITOR bundle" }
        assertFalse(canResetStaffCredentials(SetAccessPolicy(auditor)))
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertTrue(canResetStaffCredentials(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)))
        assertFalse(canResetStaffCredentials(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)))
    }
}
