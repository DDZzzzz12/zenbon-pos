package org.example.project.admin

import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the truncation defect that adminApp's `EuroFormat.formatSignedEuro` shipped.
 *
 * It computed `(abs(amount) * 100).toLong()`; `16.9 * 100` is `1689.9999999999998` in IEEE-754,
 * so `toLong()` gave `1689` and it rendered `"16.89 €"`. Measured across 0.00-20000.00, 131,252
 * of 2,000,001 amounts came out one cent low, the first being 0.29.
 *
 * These are characterization tests, not a RED gate: `formatMoneyForDisplay` lives in `shared-ui`
 * and is already covered by `MoneyDisplayTest` there. They exist so the defect stays pinned from
 * adminApp's own suite, which is where it shipped — `shared-ui` is a shared module and a future
 * change to its formatting contract should have to break an adminApp test too.
 */
class MoneyDisplayRegressionTest {
    @Test
    fun theTruncationDefectIsFixed() {
        assertEquals("+16,90 €", formatMoneyForDisplay("16.9", "EUR", forceSign = true))
        assertEquals("+1,15 €", formatMoneyForDisplay("1.15", "EUR", forceSign = true))
        // The first amount the old formatter got wrong.
        assertEquals("+0,29 €", formatMoneyForDisplay("0.29", "EUR", forceSign = true))
    }

    @Test
    fun negativeKassenbuchEntriesKeepTheirSign() {
        assertEquals("-16,90 €", formatMoneyForDisplay("-16.90", "EUR", forceSign = true))
    }
}
