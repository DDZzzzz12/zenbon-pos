package org.example.project.features.contract

import org.example.project.features.contract.data.service.isWithinTolerance
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether a changed amount is worth telling anybody about.
 *
 * Pure, so every edge case is two lines — and the edges are where this matters: a band that is too
 * tight cries wolf on a rounding difference, and one that is too loose lets 89,00 become 129,00
 * unremarked. Both failures end with the alerts being ignored.
 */
@DisplayName("Tolerance band")
class ToleranceBandTest {
    private fun within(
        expected: String,
        actual: String,
        pct: String? = null,
        abs: String? = null,
    ) = isWithinTolerance(
        expected = BigDecimal(expected),
        actual = BigDecimal(actual),
        tolerancePct = pct?.let { BigDecimal(it) },
        toleranceAbs = abs?.let { BigDecimal(it) },
    )

    @Test
    fun `with no tolerance the match must be exact`() {
        assertTrue(within("1450.00", "1450.0000"), "scale must not matter")
        assertFalse(
            within("1450.00", "1455.00"),
            "a fixed rent that changed is exactly the case worth flagging",
        )
        assertFalse(within("1450.00", "1450.01"))
    }

    @Test
    fun `a percentage band admits both directions`() {
        assertTrue(within("100.00", "110.00", pct = "10"))
        assertTrue(within("100.00", "90.00", pct = "10"))
        assertFalse(within("100.00", "110.01", pct = "10"))
        assertFalse(within("100.00", "89.99", pct = "10"))
    }

    @Test
    fun `the boundary itself is inside the band`() {
        assertTrue(within("100.00", "110.00", pct = "10"), "exactly at the limit is not yet an alert")
        assertTrue(within("100.00", "105.00", abs = "5.00"))
    }

    @Test
    fun `an absolute band admits both directions`() {
        assertTrue(within("40.00", "44.00", abs = "5.00"))
        assertTrue(within("40.00", "36.00", abs = "5.00"))
        assertFalse(within("40.00", "46.00", abs = "5.00"))
    }

    @Test
    fun `with both set the wider one wins`() {
        // 10 % of 40,00 is 4,00; the absolute 5,00 is wider, so 44,50 is inside.
        assertTrue(
            within("40.00", "44.50", pct = "10", abs = "5.00"),
            "setting two tolerances must not make the stricter one silently govern",
        )
        // 10 % of 400,00 is 40,00, now wider than the absolute 5,00.
        assertTrue(within("400.00", "430.00", pct = "10", abs = "5.00"))
        assertFalse(within("400.00", "441.00", pct = "10", abs = "5.00"))
    }

    @Test
    fun `a zero expected amount admits only zero unless a band says otherwise`() {
        assertTrue(within("0.00", "0.0000"))
        assertFalse(within("0.00", "1.00"))
        assertTrue(within("0.00", "1.00", abs = "1.00"))
    }
}
