package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyDisplayTest {
    @Test
    fun formatsWithTheGivenCurrencySymbol() {
        assertEquals("16,90 €", formatMoneyForDisplay("16.90", "EUR"))
        assertEquals("16,90 CHF", formatMoneyForDisplay("16.90", "CHF"))
    }

    @Test
    fun roundsRatherThanTruncating() {
        // The defect this replaces: adminApp's formatEuro renders 16.9 as "16.89 €"
        // because (16.9 * 100).toLong() is 1689.
        assertEquals("16,90 €", formatMoneyForDisplay("16.9", "EUR"))
        assertEquals("1,15 €", formatMoneyForDisplay("1.15", "EUR"))
        assertEquals("16,90 €", formatMoneyForDisplay("16.895", "EUR"))
    }

    @Test
    fun collapsesScaleFourToTwoDecimals() {
        // The server's DECIMAL(19,4) can arrive as "16.9000"; it must not render raw.
        assertEquals("16,90 €", formatMoneyForDisplay("16.9000", "EUR"))
    }

    /**
     * The minus must be ASCII U+002D, not U+2212.
     *
     * adminApp's CJK font subset has no U+2212 and Compose does not fall back by glyph coverage, so
     * the typographic minus rendered as nothing at all — a debit was drawn identically to a credit.
     * Asserted by codepoint rather than by literal, because the two signs are visually near-identical
     * in a diff and a well-meaning "typography fix" would otherwise sail through review.
     */
    @Test
    fun rendersNegativesWithAnAsciiMinusTheFontActuallyHas() {
        val rendered = formatMoneyForDisplay("-16.90", "EUR")
        assertEquals("-16,90 €", rendered)
        assertEquals('\u002D', rendered.first(), "the minus must be ASCII, not U+2212")
        assertTrue('\u2212' !in rendered, "U+2212 is absent from adminApp's font subset")
    }

    @Test
    fun forceSignAddsAPlusForNonNegative() {
        assertEquals("+16,90 €", formatMoneyForDisplay("16.90", "EUR", forceSign = true))
        assertEquals("-16,90 €", formatMoneyForDisplay("-16.90", "EUR", forceSign = true))
    }

    /**
     * Emulator pass 4, NOTE-3: the Kassenbuch card read "Ausgaben -0,00 €". A zero is neither a
     * credit nor a debit, so no display may sign it — not with forceSign, not from a negative zero,
     * and not from a negative amount that rounds to zero at two decimals.
     */
    @Test
    fun aZeroNeverCarriesASign() {
        assertEquals("0,00 €", formatMoneyForDisplay("0", "EUR", forceSign = true))
        assertEquals("0,00 €", formatMoneyForDisplay("0.0000", "EUR", forceSign = true))
        // A negative amount that rounds to zero at two decimals ("-0.004") must not print "-0,00".
        // (A literal "-0.00" never reaches here: :platform-money's parser rejects negative zero outright.)
        assertEquals("0,00 €", formatMoneyForDisplay("-0.004", "EUR"))
        assertEquals("0,00 €", formatMoneyForDisplay("-0.004", "EUR", forceSign = true))
        assertEquals("0,00 €", formatMoneyForDisplay("0.004", "EUR", forceSign = true))
        assertEquals("0,00", formatMoneyForDisplay("0", "", forceSign = true))
        // The smallest non-zero display amounts keep their sign.
        assertEquals("-0,01 €", formatMoneyForDisplay("-0.005", "EUR"))
        assertEquals("+0,01 €", formatMoneyForDisplay("0.01", "EUR", forceSign = true))
    }

    @Test
    fun groupsThousands() {
        assertEquals("1.234,56 €", formatMoneyForDisplay("1234.56", "EUR"))
    }

    @Test
    fun malformedInputRendersAPlaceholderRatherThanCrashing() {
        // A display must never take down the screen. Unknown values render as an em dash.
        assertEquals("—", formatMoneyForDisplay("not-a-number", "EUR"))
        assertEquals("—", formatMoneyForDisplay("", "EUR"))
    }

    @Test
    fun envelopeOverflowFromRoundingCarryRendersThePlaceholder() {
        // parse() succeeds at exactly 18 integer digits, then HALF_UP carries to 19 and
        // setScale's envelope guard throws. Both calls must be inside the try.
        assertEquals("—", formatMoneyForDisplay("999999999999999999.995", "EUR"))
    }

    @Test
    fun unknownCurrencyFallsBackToTheRawCodeRatherThanCrashing() {
        assertEquals("16,90 XYZ", formatMoneyForDisplay("16.90", "XYZ"))
        assertEquals("16,90", formatMoneyForDisplay("16.90", ""))
    }
}
