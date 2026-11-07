package org.example.project.infrastructure.plugins

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Defect D3: `hasAnyRole` lacked the ADMIN escape that `hasRole` had.
 *
 * Latent rather than live — `hasAnyRole` has no call sites — but the inconsistency is a trap for
 * the group work, so both helpers now route through one predicate.
 */
@DisplayName("Role helpers treat ADMIN consistently")
class RoleSatisfiesTest {
    @Test
    fun `admin satisfies any requirement`() {
        assertTrue(roleSatisfies(EmployeeRole.ADMIN, setOf(EmployeeRole.CASHIER)))
        assertTrue(roleSatisfies(EmployeeRole.ADMIN, setOf(EmployeeRole.MANAGER)))
        assertTrue(roleSatisfies(EmployeeRole.ADMIN, emptySet()))
    }

    @Test
    fun `a member of the required set satisfies it`() {
        assertTrue(roleSatisfies(EmployeeRole.CASHIER, setOf(EmployeeRole.CASHIER, EmployeeRole.MANAGER)))
    }

    @Test
    fun `a non-member without admin does not`() {
        assertFalse(roleSatisfies(EmployeeRole.TERMINAL_DEVICE, setOf(EmployeeRole.MANAGER)))
        assertFalse(roleSatisfies(EmployeeRole.CASHIER, setOf(EmployeeRole.MANAGER)))
    }

    @Test
    fun `an empty requirement admits only admin`() {
        // Fails closed: a requirement assembled dynamically that comes out empty must not open.
        assertFalse(roleSatisfies(EmployeeRole.MANAGER, emptySet()))
    }
}
