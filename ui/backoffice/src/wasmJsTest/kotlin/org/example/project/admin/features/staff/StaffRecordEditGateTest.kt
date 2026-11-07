package org.example.project.admin.features.staff

import org.example.project.admin.core.auth.canEditStaffRecords
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The staff editor shows personnel number, notes and training mode read-only without
 * `EMPLOYEE_WRITE` — the server refuses a change to them without it, also on one's own record.
 */
class StaffRecordEditGateTest {
    @Test
    fun employeeWriteEditsAdministrativeFields() {
        assertTrue(canEditStaffRecords(SetAccessPolicy(setOf(Capability.EMPLOYEE_WRITE))))
    }

    @Test
    fun employeeReadAloneDoesNot() {
        assertFalse(canEditStaffRecords(SetAccessPolicy(setOf(Capability.EMPLOYEE_READ, Capability.SELF_WRITE))))
    }

    @Test
    fun cashierCannotManagerAndAdminCan() {
        assertFalse(canEditStaffRecords(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.CASHIER))))
        assertTrue(canEditStaffRecords(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.MANAGER))))
        assertTrue(canEditStaffRecords(SetAccessPolicy(CapabilityBundles.forRole(EmployeeRole.ADMIN))))
    }

    @Test
    fun theAuditorReadsStaffButCannotEditThem() {
        val auditor = requireNotNull(CapabilityBundles.forGroup("AUDITOR")) { "no AUDITOR bundle" }
        assertTrue(Capability.EMPLOYEE_READ in auditor)
        assertFalse(canEditStaffRecords(SetAccessPolicy(auditor)))
    }
}
