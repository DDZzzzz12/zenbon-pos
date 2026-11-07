package org.example.project.ui.screens.reports.components

import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.ui.components.formatMoneyForDisplay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the counted-cash prefill in `CreateZBonConfirmDialog`.
 *
 * The dialog seeds its counted-cash field from the expected drawer balance, and then validates that
 * same field with [toCanonicalMoneyOrNull], which is only
 * `Decimal.parseOrNull(trim().replace(",", "."))?.toPlainString()` — it swaps "," for "." and does
 * nothing else. So the prefill has to survive that round trip, or the field the dialog just filled
 * in reads as invalid and `enabled = !isInvalid` turns Confirm OFF.
 *
 * That is operator-blocking rather than cosmetic: a Z-Bon is a daily closing, and the only escape
 * from a disabled Confirm is to clear a field the operator did not type into. The obvious
 * implementation — `formatMoneyForDisplay(balance, "EUR")` — does exactly that, and at four figures
 * it is worse, because the grouping dot survives the comma swap as a second decimal point. The last
 * test below pins that defect itself, rather than only its absence.
 *
 * `ZBonViewModelTest` covers the value handed TO the dialog; nothing covered the field's own state.
 */
class ZBonCountedPrefillTest {
    /**
     * The dialog's own Confirm gate, mirrored from `CreateZBonConfirmDialog`:
     *
     * ```
     * val trimmed = counted.trim()
     * val canonical = trimmed.takeIf { it.isNotEmpty() }?.toCanonicalMoneyOrNull()
     * val isInvalid = trimmed.isNotEmpty() && canonical == null
     * ```
     *
     * A mirror, not the dialog's own lines — the gate lives inside a composable and cannot be
     * called from here. Keep the two in step.
     */
    private fun confirmEnabled(counted: String): Boolean {
        val trimmed = counted.trim()
        val canonical = trimmed.takeIf { it.isNotEmpty() }?.toCanonicalMoneyOrNull()
        return !(trimmed.isNotEmpty() && canonical == null)
    }

    @Test
    fun `a plain balance round-trips back to its canonical form`() {
        assertEquals("130,00", zBonCountedPrefill("130.00"))
        assertEquals("130.00", zBonCountedPrefill("130.00").toCanonicalMoneyOrNull())
    }

    /**
     * The four-figure case is the one the currency formatter breaks hardest: its grouping dot is
     * still a dot after the comma swap, so the validator sees two decimal points.
     */
    @Test
    fun `a four-figure balance is not grouped, so it still round-trips`() {
        assertEquals("1234,56", zBonCountedPrefill("1234.56"))
        assertEquals("1234.56", zBonCountedPrefill("1234.56").toCanonicalMoneyOrNull())
    }

    /**
     * Normalised to two decimals, matching the expected-cash line the operator is reading it
     * against. The round trip is on VALUE, not on the input's scale: "130" comes back "130.00".
     */
    @Test
    fun `a balance with no decimals is normalised to two, and round-trips`() {
        assertEquals("130,00", zBonCountedPrefill("130"))
        assertEquals("130.00", zBonCountedPrefill("130").toCanonicalMoneyOrNull())
    }

    @Test
    fun `a sub-euro balance round-trips`() {
        assertEquals("0,05", zBonCountedPrefill("0.05"))
        assertEquals("0.05", zBonCountedPrefill("0.05").toCanonicalMoneyOrNull())
    }

    /**
     * A failed balance lookup must never block a closing, so the field degrades to EMPTY — which
     * the dialog reads as "did not count" and always allows through.
     */
    @Test
    fun `a missing balance leaves the field empty so the Z-Bon can still be closed`() {
        assertEquals("", zBonCountedPrefill(null))
        assertTrue(confirmEnabled(zBonCountedPrefill(null)))
    }

    /**
     * Empty, NOT a "—" placeholder: a placeholder is non-empty, fails the validator, and disables
     * Confirm — the closing would be blocked by a value the operator never typed.
     */
    @Test
    fun `an unparseable balance leaves the field empty rather than a placeholder`() {
        assertEquals("", zBonCountedPrefill("n/a"))
        assertEquals("", zBonCountedPrefill(""))
        assertTrue(confirmEnabled(zBonCountedPrefill("n/a")))
    }

    /**
     * The assertion aimed squarely at the original bug: a currency symbol or a grouping separator
     * in the prefill is what breaks the round trip.
     */
    @Test
    fun `the prefill carries no currency symbol and no grouping separator`() {
        val prefill = zBonCountedPrefill("1234.56")

        assertFalse(prefill.contains("€"), "a currency symbol does not survive toCanonicalMoneyOrNull")
        assertFalse(prefill.contains(" "), "a space does not survive toCanonicalMoneyOrNull")
        assertEquals(
            1,
            prefill.count { it == ',' || it == '.' },
            "exactly one separator: a grouping dot becomes a second decimal point after the comma swap",
        )
    }

    /** The operator-facing property all of the above exists to protect. */
    @Test
    fun `Confirm is enabled on every balance the dialog can open with`() {
        listOf("130.00", "1234.56", "130", "0.05", "0.00", "99999.99").forEach { balance ->
            assertTrue(
                confirmEnabled(zBonCountedPrefill(balance)),
                "Confirm must not open disabled on the value the dialog itself suggested (balance=$balance)",
            )
        }
    }

    /**
     * Documents the defect rather than only asserting its absence: this is what the obvious
     * implementation produced, and why Confirm opened disabled on its own suggestion.
     */
    @Test
    fun `the old formatMoneyForDisplay prefill did not round-trip`() {
        assertEquals("1.234,56 €", formatMoneyForDisplay("1234.56", "EUR"))
        assertNull(
            formatMoneyForDisplay("1234.56", "EUR").toCanonicalMoneyOrNull(),
            "\"1.234,56 €\" -> \"1.234.56 €\": multiple decimal points, so the field read as invalid",
        )
        assertNull(formatMoneyForDisplay("130.00", "EUR").toCanonicalMoneyOrNull())
        assertFalse(confirmEnabled(formatMoneyForDisplay("130.00", "EUR")))
        // And the unparseable case seeded a placeholder, which is non-empty and equally invalid.
        assertEquals("—", formatMoneyForDisplay("n/a", "EUR"))
        assertFalse(confirmEnabled(formatMoneyForDisplay("n/a", "EUR")))
    }
}
