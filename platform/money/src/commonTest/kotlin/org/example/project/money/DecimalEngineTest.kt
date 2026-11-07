package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Engine-level tests. These run on EVERY target, so each engine must satisfy them. */
class DecimalEngineTest {
    private fun handle(plain: String) = DecimalEngine.fromPlainString(DecimalParser.parse(plain).plain)

    private fun round(plain: String) = DecimalEngine.toPlainString(handle(plain))

    /** Asserts the rendered value, its scale, and that no engine slipped into exponent notation. */
    private fun assertPlain(
        expected: String,
        expectedScale: Int,
        h: DecimalHandle,
    ) {
        val text = DecimalEngine.toPlainString(h)
        // Checked before the equality assertion: kotlin.test renders the actual value inside
        // angle brackets, which the Karma XML reporter drops, so an exponent-notation failure
        // would otherwise be unreadable on the wasmJs leg.
        assertFalse(text.contains('e'), "exponent notation in \"$text\"")
        assertFalse(text.contains('E'), "exponent notation in \"$text\"")
        assertEquals(expected, text)
        assertEquals(expectedScale, DecimalEngine.scaleOf(h), "scale of \"$text\"")
    }

    /** Asserts a zero-valued result that must carry no sign at all. */
    private fun assertUnsignedZero(
        expected: String,
        h: DecimalHandle,
    ) {
        val text = DecimalEngine.toPlainString(h)
        // Sign check first, for the reason given in [assertPlain]: a leaked "-0.00" is invisible
        // in the wasmJs XML report if kotlin.test reports it as the `actual` value.
        assertFalse(text.startsWith("-"), "negative zero leaked: \"$text\"")
        assertEquals(expected, text)
        assertEquals(0, DecimalEngine.signumOf(h), "signum of \"$text\"")
    }

    @Test
    fun roundTripsPlainStringsPreservingScale() {
        assertEquals("16.90", round("16.90"))
        assertEquals("16.9000", round("16.9000"))
        assertEquals("17", round("17"))
        assertEquals("0.00", round("0.00"))
        assertEquals("-3.50", round("-3.50"))
    }

    @Test
    fun addUsesMaxScale() {
        val r = DecimalEngine.add(handle("1.5"), handle("1.25"))
        assertEquals("2.75", DecimalEngine.toPlainString(r))
        assertEquals(2, DecimalEngine.scaleOf(r))
    }

    @Test
    fun subtractUsesMaxScale() {
        val r = DecimalEngine.subtract(handle("2.75"), handle("1.5"))
        assertEquals("1.25", DecimalEngine.toPlainString(r))
    }

    @Test
    fun multiplySumsScales() {
        val r = DecimalEngine.multiply(handle("1.5"), handle("1.25"))
        assertEquals("1.875", DecimalEngine.toPlainString(r))
        assertEquals(3, DecimalEngine.scaleOf(r))
    }

    @Test
    fun divideHonoursExplicitScaleAndMode() {
        val r = DecimalEngine.divide(handle("1"), handle("3"), 4, MoneyRounding.HALF_UP)
        assertEquals("0.3333", DecimalEngine.toPlainString(r))
    }

    @Test
    fun setScaleRoundsHalfUpAwayFromZero() {
        assertEquals(
            "2.5",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("2.45"), 1, MoneyRounding.HALF_UP),
            ),
        )
        assertEquals(
            "-2.5",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-2.45"), 1, MoneyRounding.HALF_UP),
            ),
        )
    }

    @Test
    fun setScaleHalfEvenPicksEvenNeighbour() {
        assertEquals(
            "2",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("2.5"), 0, MoneyRounding.HALF_EVEN),
            ),
        )
        assertEquals(
            "4",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("3.5"), 0, MoneyRounding.HALF_EVEN),
            ),
        )
    }

    @Test
    fun downAndUpAreRelativeToZeroNotInfinity() {
        // The Apple trap: NSRoundDown/NSRoundUp mean FLOOR/CEILING. DOWN/UP must not.
        assertEquals(
            "-2",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-2.9"), 0, MoneyRounding.DOWN),
            ),
        )
        assertEquals(
            "-3",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-2.1"), 0, MoneyRounding.UP),
            ),
        )
        assertEquals(
            "2",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("2.9"), 0, MoneyRounding.DOWN),
            ),
        )
        assertEquals(
            "3",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("2.1"), 0, MoneyRounding.UP),
            ),
        )
    }

    @Test
    fun floorAndCeilingAreRelativeToInfinity() {
        assertEquals(
            "-3",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-2.1"), 0, MoneyRounding.FLOOR),
            ),
        )
        assertEquals(
            "-2",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-2.9"), 0, MoneyRounding.CEILING),
            ),
        )
    }

    @Test
    fun divisionKeepsEnoughWorkingPrecisionForWideQuotients() {
        // Pins the wasm engine's `precision: 40` clone config, which nothing else covers: every
        // divide vector in the golden corpus runs at scale 0-4 and would pass at decimal.js's
        // DEFAULT precision of 20. This quotient needs 21 significant digits (9 integer + 12
        // fractional), so at precision 20 decimal.js returns "333333333.333333333330" — the
        // final digit lost. Measured, not hypothesised.
        assertEquals(
            "333333333.333333333333",
            DecimalEngine.toPlainString(
                DecimalEngine.divide(handle("1000000000"), handle("3"), 12, MoneyRounding.HALF_UP),
            ),
        )
        // Worst case inside the envelope: 18 integer + 12 fractional = 30 significant digits.
        assertEquals(
            "333333333333333332.666666666667",
            DecimalEngine.toPlainString(
                DecimalEngine.divide(handle("999999999999999998"), handle("3"), 12, MoneyRounding.HALF_UP),
            ),
        )
    }

    @Test
    fun comparesByValueAcrossScales() {
        assertEquals(0, DecimalEngine.compare(handle("16.9"), handle("16.9000")))
        assertEquals(-1, DecimalEngine.compare(handle("16.9"), handle("16.91")))
        assertEquals(1, DecimalEngine.compare(handle("16.92"), handle("16.91")))
    }

    @Test
    fun reportsScalePrecisionAndSignum() {
        assertEquals(4, DecimalEngine.scaleOf(handle("16.9000")))
        assertEquals(6, DecimalEngine.precisionOf(handle("16.9000")))
        assertEquals(2, DecimalEngine.precisionOf(handle("0.0019")))
        assertEquals(-1, DecimalEngine.signumOf(handle("-1.00")))
        assertEquals(0, DecimalEngine.signumOf(handle("0.00")))
        assertEquals(1, DecimalEngine.signumOf(handle("1.00")))
    }

    /**
     * max(scaleA, scaleB) must be independent of operand order. An engine that simply adopted the
     * scale of one fixed operand would still satisfy a single-orientation test, so both operators
     * are exercised with the wider scale on the left, on the right, and on neither.
     */
    @Test
    fun addAndSubtractUseMaxScaleWhicheverOperandCarriesIt() {
        // add — wider scale on the right, on the left, then equal scales.
        assertPlain("2.75", 2, DecimalEngine.add(handle("1.5"), handle("1.25")))
        assertPlain("2.75", 2, DecimalEngine.add(handle("1.25"), handle("1.5")))
        assertPlain("2.75", 2, DecimalEngine.add(handle("1.50"), handle("1.25")))

        // add — wide gap in both orders, and a scale-0 operand that must not lower the result.
        assertPlain("2.5001", 4, DecimalEngine.add(handle("2.5"), handle("0.0001")))
        assertPlain("2.5001", 4, DecimalEngine.add(handle("0.0001"), handle("2.5")))
        assertPlain("7.250", 3, DecimalEngine.add(handle("7"), handle("0.250")))
        assertPlain("7.250", 3, DecimalEngine.add(handle("0.250"), handle("7")))

        // subtract — wider scale on the left, on the right, then equal scales.
        assertPlain("1.25", 2, DecimalEngine.subtract(handle("2.75"), handle("1.5")))
        assertPlain("1.25", 2, DecimalEngine.subtract(handle("2.5"), handle("1.25")))
        assertPlain("1.25", 2, DecimalEngine.subtract(handle("2.75"), handle("1.50")))

        // subtract — wide gap in both orders, and a scale-0 operand on either side.
        assertPlain("2.4999", 4, DecimalEngine.subtract(handle("2.5"), handle("0.0001")))
        assertPlain("-2.4999", 4, DecimalEngine.subtract(handle("0.0001"), handle("2.5")))
        assertPlain("6.750", 3, DecimalEngine.subtract(handle("7"), handle("0.250")))
        assertPlain("-6.750", 3, DecimalEngine.subtract(handle("0.250"), handle("7")))
    }

    /**
     * Plain notation at the envelope corners, where every engine's default renderer flips to
     * exponent notation: java.math.BigDecimal.toString() gives "1E-12", and decimal.js toString()
     * gives "1e-12" under its default toExpNeg of -7 (its toFixed() never does, and the shim's
     * clone additionally pushes toExpNeg/toExpPos out of reach). So this catches the
     * toString()-instead-of-fixed-point slip in whichever engine makes it.
     */
    @Test
    fun toPlainStringNeverUsesExponentNotationAtEnvelopeBoundaries() {
        // scale 12 == DecimalEnvelope.MAX_SCALE
        assertPlainThroughEveryPrimitive("0.000000000001", 12)
        assertPlainThroughEveryPrimitive("-0.000000000001", 12)

        // 18 integer digits == DecimalEnvelope.MAX_INT_DIGITS
        assertPlainThroughEveryPrimitive("999999999999999999", 0)
        assertPlainThroughEveryPrimitive("-999999999999999999", 0)

        // both corners at once: 30 significant digits, 18 integer plus 12 fractional
        assertPlainThroughEveryPrimitive("999999999999999999.999999999999", 12)
        assertPlainThroughEveryPrimitive("-999999999999999999.999999999999", 12)

        // negation of the smallest representable value, in the 0 - x shape Decimal uses
        assertPlain(
            "-0.000000000001",
            12,
            DecimalEngine.subtract(handle("0"), handle("0.000000000001")),
        )
    }

    /**
     * Routes one value through every string-returning primitive with a value-preserving operand,
     * so the check binds no matter which primitive an engine formats badly.
     */
    private fun assertPlainThroughEveryPrimitive(
        plain: String,
        scale: Int,
    ) {
        val h = handle(plain)
        assertPlain(plain, scale, h)
        assertPlain(plain, scale, DecimalEngine.add(h, handle("0")))
        assertPlain(plain, scale, DecimalEngine.subtract(h, handle("0")))
        assertPlain(plain, scale, DecimalEngine.multiply(h, handle("1")))
        assertPlain(
            plain,
            scale,
            DecimalEngine.divide(h, handle("1"), scale, MoneyRounding.HALF_UP),
        )
        assertPlain(plain, scale, DecimalEngine.setScale(h, scale, MoneyRounding.HALF_UP))
    }

    /**
     * Exactly -1 / 0 / 1, never merely the sign — the iOS engine has to normalise
     * NSComparisonResult down to these three values.
     */
    @Test
    fun comparesNegativeAndMixedSignOperands() {
        // negative equality across scales
        assertEquals(0, DecimalEngine.compare(handle("-16.9"), handle("-16.9000")))
        assertEquals(0, DecimalEngine.compare(handle("-16.9000"), handle("-16.9")))

        // both negative: the one closer to zero is the greater
        assertEquals(-1, DecimalEngine.compare(handle("-16.91"), handle("-16.9")))
        assertEquals(1, DecimalEngine.compare(handle("-16.9"), handle("-16.91")))
        assertEquals(-1, DecimalEngine.compare(handle("-2.00"), handle("-1.9999")))
        assertEquals(1, DecimalEngine.compare(handle("-1.9999"), handle("-2.00")))

        // mixed signs — a magnitude-only comparison would invert these
        assertEquals(-1, DecimalEngine.compare(handle("-0.01"), handle("0.01")))
        assertEquals(1, DecimalEngine.compare(handle("0.01"), handle("-0.01")))
        assertEquals(-1, DecimalEngine.compare(handle("-1"), handle("1")))
        assertEquals(-1, DecimalEngine.compare(handle("-100.00"), handle("0.01")))
        assertEquals(1, DecimalEngine.compare(handle("0.01"), handle("-100.00")))

        // against zero, across scales
        assertEquals(-1, DecimalEngine.compare(handle("-0.0001"), handle("0")))
        assertEquals(1, DecimalEngine.compare(handle("0.000"), handle("-0.0001")))
        assertEquals(0, DecimalEngine.compare(handle("0"), handle("0.0000")))
    }

    /**
     * VERIFIED DIVERGENCE this pins:
     *   java.math.BigDecimal("-0.004").setScale(2, HALF_UP).toPlainString() == "0.00"
     *   raw decimal.js new D("-0.004").toFixed(2, 4)                        == "-0.00"
     * Every engine must show the BigDecimal result. NSDecimalNumber has the same signed-zero
     * hazard, so this is engine contract, not a wasm detail.
     */
    @Test
    fun negativeZeroIsNormalisedOutOfEngineResults() {
        val towardsZero =
            listOf(
                MoneyRounding.HALF_UP,
                MoneyRounding.HALF_EVEN,
                MoneyRounding.DOWN,
                MoneyRounding.CEILING,
            )
        for (plain in listOf("-0.004", "-0.001", "-0.0004")) {
            for (mode in towardsZero) {
                assertUnsignedZero(
                    "0.00",
                    DecimalEngine.setScale(handle(plain), 2, mode),
                )
            }
        }

        // The guard must not over-reach: a legitimately negative result keeps its sign.
        assertEquals(
            "-0.01",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-0.004"), 2, MoneyRounding.FLOOR),
            ),
        )
        assertEquals(
            "-0.01",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-0.0004"), 2, MoneyRounding.FLOOR),
            ),
        )
        assertEquals(
            "-0.01",
            DecimalEngine.toPlainString(
                DecimalEngine.setScale(handle("-0.004"), 2, MoneyRounding.UP),
            ),
        )

        // Products and quotients that land on zero with a negative operand.
        assertUnsignedZero("0.00", DecimalEngine.multiply(handle("0.00"), handle("-1")))
        assertUnsignedZero("0.00", DecimalEngine.multiply(handle("-1"), handle("0.00")))
        assertUnsignedZero(
            "0.00",
            DecimalEngine.divide(handle("0.00"), handle("-5"), 2, MoneyRounding.HALF_UP),
        )

        // A non-zero negative quotient that rounds to zero must also lose the sign.
        assertUnsignedZero(
            "0.00",
            DecimalEngine.divide(handle("-1"), handle("1000"), 2, MoneyRounding.HALF_UP),
        )
        // ...while FLOOR on the same quotient stays negative.
        assertEquals(
            "-0.01",
            DecimalEngine.toPlainString(
                DecimalEngine.divide(handle("-1"), handle("1000"), 2, MoneyRounding.FLOOR),
            ),
        )
    }
}
