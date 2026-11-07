package org.example.project.admin.features.kassenbuch

import org.example.project.admin.features.kassenbuch.presentation.CashbookAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.resolveAccessPolicy
import org.example.project.core.auth.toCapabilitiesOrNull
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/** FO-03 / FO-05: posting follows `CASHBOOK_WRITE`, the Kassensturz follows `CASHBOOK_CLOSE`. */
class CashbookAccessTest {
    @Test
    fun a_read_only_group_is_offered_neither_posting_nor_closing() {
        assertEquals(
            CashbookAccess(mayPost = false, mayClose = false),
            CashbookAccess.of(SetAccessPolicy(setOf(Capability.CASHBOOK_READ))),
        )
    }

    @Test
    fun posting_and_closing_are_separate_capabilities() {
        assertEquals(
            CashbookAccess(mayPost = true, mayClose = false),
            CashbookAccess.of(SetAccessPolicy(setOf(Capability.CASHBOOK_READ, Capability.CASHBOOK_WRITE))),
        )
        assertEquals(
            CashbookAccess(mayPost = false, mayClose = true),
            CashbookAccess.of(SetAccessPolicy(setOf(Capability.CASHBOOK_READ, Capability.CASHBOOK_CLOSE))),
        )
    }

    @Test
    fun an_unreported_capability_set_follows_the_role_fallback() {
        val unknown = emptySet<String>().toCapabilitiesOrNull()

        assertEquals(
            CashbookAccess(mayPost = true, mayClose = true),
            CashbookAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.MANAGER)),
        )
        assertEquals(
            CashbookAccess(mayPost = false, mayClose = false),
            CashbookAccess.of(resolveAccessPolicy(unknown, null, EmployeeRole.CASHIER)),
        )
    }
}
