package org.example.project.ui.screens.order

import org.example.project.core.auth.PriceAuthority
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.money.Decimal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two findings from the 2026-09-27 emulator pass, both in `PosEditItemPanel`.
 *
 * **E-2** — the order station's edit panel offered „Rabatt", and the server booked the 10 % it sent.
 * [linePricingOffered] is what hides the discount field and the custom-price toggle; it reads the
 * station marker (`TokenStore.isStation`), which is set exactly for the session registered under the
 * station account — `TERMINAL_DEVICE`, the role `PriceAuthority.mayPriceLinesAs` refuses on the
 * server. UI may not branch on the role itself (`NoRoleGatesInScreensTest`).
 *
 * **REG-2** — an invalid discount (25 under a 20 % cap) showed the line total as „0,00 €", as if the
 * line were free. [displayedLineTotal] shows it undiscounted instead.
 */
class EditPanelStationPricingTest {
    @Test
    fun `the station account is offered no line pricing`() {
        assertFalse(linePricingOffered(isStation = true))
        // A station session is always stored as TERMINAL_DEVICE (TokenStore.storeStationSession):
        // the client's answer must be the server's for that role.
        assertFalse(PriceAuthority.mayPriceLinesAs(EmployeeRole.TERMINAL_DEVICE))
    }

    @Test
    fun `a person keeps the discount and custom-price controls on any device`() {
        // A person's login clears the station marker, the order-station device's included.
        assertTrue(linePricingOffered(isStation = false))
        assertTrue(PriceAuthority.mayPriceLinesAs(EmployeeRole.MANAGER))
        assertTrue(PriceAuthority.mayPriceLinesAs(EmployeeRole.CASHIER))
    }

    @Test
    fun `an unusable discount shows the undiscounted line, not zero`() {
        // Wein Flasche 10,00 €, Rabatt 25 under a 20 % cap: discountPercentOrNull rejects it.
        val rejected = discountPercentOrNull("25", maxDiscountPercent(canApplyHighDiscount = false))
        assertNull(rejected, "the premise: 25 is refused under the 20 % cap")

        assertEquals("10.00", displayedLineTotal(Decimal.parse("10.00"), 1, discountMultiplier = null)?.toPlainString())
        assertEquals("20.00", displayedLineTotal(Decimal.parse("10.00"), 2, discountMultiplier = null)?.toPlainString())
    }

    @Test
    fun `a usable discount is still shown discounted`() {
        // 20 % off 10,00 € -> 8,00 €, as the pass saw it.
        assertEquals("8.00", displayedLineTotal(Decimal.parse("10.00"), 1, Decimal.parse("0.80"))?.toPlainString())
        // Tagesmenü 16,90 € at 10 % off -> 15,21 €.
        assertEquals("15.21", displayedLineTotal(Decimal.parse("16.90"), 1, Decimal.parse("0.90"))?.toPlainString())
    }

    @Test
    fun `a half-typed custom price still shows no total`() {
        assertNull(displayedLineTotal(unitPriceWithExtras = null, quantity = 1, discountMultiplier = Decimal.ONE))
    }
}
