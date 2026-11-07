package org.example.project.admin.features.contracts

import org.example.project.admin.features.contracts.presentation.ContractsAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03: the Verträge write controls follow `CONTRACT_WRITE`. */
class ContractsAccessTest {
    @Test
    fun holding_contract_write_offers_the_write_controls() {
        assertEquals(ContractsAccess(mayWrite = true), ContractsAccess.of(SetAccessPolicy(setOf(Capability.CONTRACT_WRITE))))
    }

    @Test
    fun a_read_only_group_is_offered_none() {
        assertEquals(ContractsAccess(mayWrite = false), ContractsAccess.of(SetAccessPolicy(setOf(Capability.CONTRACT_READ))))
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(ContractsAccess(mayWrite = true), ContractsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)))
        assertEquals(ContractsAccess(mayWrite = false), ContractsAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)))
    }
}
