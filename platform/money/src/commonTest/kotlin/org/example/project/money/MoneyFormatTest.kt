package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyFormatTest {
    @Test
    fun plainFormatsTwoFractionDigits() {
        assertEquals("16.90", Decimal.parse("16.9").format())
        assertEquals("16.90", Decimal.parse("16.90").format())
        assertEquals("17.00", Decimal.parse("17").format())
    }

    @Test
    fun roundsRatherThanTruncates() {
        // The defect this module replaces: 16.9 must never render as 16.89.
        assertEquals("16.90", Decimal.parse("16.9").format())
        assertEquals("1.15", Decimal.parse("1.15").format())
        assertEquals("16.90", Decimal.parse("16.895").format())
        assertEquals("17.00", Decimal.parse("16.999").format())
    }

    @Test
    fun padsShortScales() {
        assertEquals("16.90", Decimal.parse("16.9").format())
        assertEquals("0.00", Decimal.parse("0").format())
    }

    @Test
    fun rendersNegativesWithConfiguredMinusSign() {
        assertEquals("-16.90", Decimal.parse("-16.9").format())
        assertEquals(
            "−16.90",
            Decimal.parse("-16.9").format(
                MoneyFormat.PLAIN.copy(minusSign = "−"),
            ),
        )
    }

    @Test
    fun forceSignAddsPlusForNonNegative() {
        val fmt = MoneyFormat.PLAIN.copy(forceSign = true)
        assertEquals("+16.90", Decimal.parse("16.9").format(fmt))
        assertEquals("+0.00", Decimal.parse("0").format(fmt))
        assertEquals("-16.90", Decimal.parse("-16.9").format(fmt))
    }

    @Test
    fun germanPresetUsesCommaAndGrouping() {
        assertEquals("1.234,56 €", Decimal.parse("1234.56").format(MoneyFormat.DE_EUR))
        assertEquals("16,90 €", Decimal.parse("16.9").format(MoneyFormat.DE_EUR))
        assertEquals("1.234.567,89 €", Decimal.parse("1234567.89").format(MoneyFormat.DE_EUR))
        assertEquals("123,00 €", Decimal.parse("123").format(MoneyFormat.DE_EUR))
    }

    /**
     * The German preset must render a negative with a sign a font can actually draw.
     *
     * It used to carry U+2212 MINUS SIGN. adminApp renders through a CJK font subset that lacks it,
     * and Compose does not fall back by glyph coverage, so the sign drew as nothing: a debit was
     * displayed identically to a credit. Asserted by codepoint, because U+2212 and U+002D are
     * indistinguishable in a diff and a typography "fix" would otherwise pass review.
     */
    @Test
    fun germanPresetsUseAMinusEveryFontHas() {
        val money = Decimal.parse("-1234.56").format(MoneyFormat.DE_EUR)
        assertEquals("-1.234,56 €", money)
        assertEquals('\u002D', money.first())
        assertTrue('\u2212' !in money, "U+2212 is absent from adminApp's font subset")

        val percent = Percentage.parse("-10.7").format(PercentFormat.DE)
        assertEquals("-10,7 %", percent)
        assertTrue('\u2212' !in percent, "U+2212 is absent from adminApp's font subset")
    }

    @Test
    fun symbolCanPrecedeWithoutSpace() {
        val fmt = MoneyFormat.PLAIN.copy(symbol = "$", symbolBefore = true)
        assertEquals("$16.90", Decimal.parse("16.9").format(fmt))
    }

    @Test
    fun moneyFallsBackToCurrencyCode() {
        val money = Money(Decimal.parse("16.9"), Currency.EUR)
        assertEquals("16.90 EUR", money.format())
        assertEquals("16,90 €", money.format(MoneyFormat.DE_EUR))
    }

    @Test
    fun honoursHigherMaxFractionDigits() {
        val fmt = MoneyFormat.PLAIN.copy(minFractionDigits = 4, maxFractionDigits = 4)
        assertEquals("16.9000", Decimal.parse("16.9").format(fmt))
    }
}
