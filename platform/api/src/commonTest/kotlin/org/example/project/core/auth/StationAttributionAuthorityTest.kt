package org.example.project.core.auth

import org.example.project.core.domain.enums.employee.EmployeeRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two station rules — billing (`BillingAuthority.mayBillAs`, finding OS-b) and line pricing
 * (`PriceAuthority.mayPriceLinesAs`, finding E-2) — key on WHO the bill or order is registered under,
 * not on the device (owner's decision, 2026-09-28). Both refuse exactly the shared station account
 * (`TERMINAL_DEVICE`, what a station JWT and a bare station device token act as) and allow every
 * person. The server and both clients read these same functions.
 */
class StationAttributionAuthorityTest {
    private val persons = setOf(EmployeeRole.CASHIER, EmployeeRole.MANAGER, EmployeeRole.ADMIN)

    @Test
    fun billingIsRefusedUnderTheStationAccountOnly() {
        val billing = EmployeeRole.entries.filter { BillingAuthority.mayBillAs(it) }.toSet()
        assertEquals(persons, billing)
    }

    @Test
    fun linePricingIsRefusedUnderTheStationAccountOnly() {
        val pricing = EmployeeRole.entries.filter { PriceAuthority.mayPriceLinesAs(it) }.toSet()
        assertEquals(persons, pricing)
    }

    @Test
    fun bothRulesDrawTheSameLine() {
        EmployeeRole.entries.forEach {
            assertEquals(BillingAuthority.mayBillAs(it), PriceAuthority.mayPriceLinesAs(it), "role $it")
        }
    }
}
