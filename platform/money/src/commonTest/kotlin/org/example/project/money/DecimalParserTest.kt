package org.example.project.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecimalParserTest {
    @Test
    fun acceptsPlainForms() {
        assertEquals("16.90", DecimalParser.parse("16.90").plain)
        assertEquals("-16.90", DecimalParser.parse("-16.90").plain)
        assertEquals("16.90", DecimalParser.parse("+16.90").plain)
        assertEquals("0", DecimalParser.parse("0").plain)
        assertEquals("0.00", DecimalParser.parse("0.00").plain)
        assertEquals("17", DecimalParser.parse("17").plain)
    }

    @Test
    fun preservesTrailingZerosAsScale() {
        assertEquals(4, DecimalParser.parse("16.9000").scale)
        assertEquals(2, DecimalParser.parse("16.90").scale)
        assertEquals(0, DecimalParser.parse("17").scale)
    }

    @Test
    fun stripsLeadingZerosButKeepsOne() {
        assertEquals("16.90", DecimalParser.parse("0016.90").plain)
        assertEquals("0.50", DecimalParser.parse("000.50").plain)
    }

    @Test
    fun computesPrecisionLikeBigDecimal() {
        assertEquals(4, DecimalParser.parse("16.90").precision)
        assertEquals(2, DecimalParser.parse("0.0019").precision)
        assertEquals(1, DecimalParser.parse("0.00").precision)
        assertEquals(1, DecimalParser.parse("0").precision)
    }

    @Test
    fun rejectsMalformedInput() {
        val bad =
            listOf(
                "",
                " ",
                " 16.90 ",
                "16.",
                ".9",
                "1,5",
                "abc",
                "NaN",
                "Infinity",
                "0x10",
                "1_000",
                "--1",
                "1.2.3",
                "1e5",
                "1E-5",
                "+",
                "-",
                "١٢",
            )
        for (input in bad) {
            assertFailsWith<DecimalParseException>("expected rejection of \"$input\"") {
                DecimalParser.parse(input)
            }
        }
    }

    @Test
    fun rejectsNegativeZero() {
        assertFailsWith<DecimalParseException> { DecimalParser.parse("-0") }
        assertFailsWith<DecimalParseException> { DecimalParser.parse("-0.00") }
    }

    @Test
    fun enforcesScaleCeiling() {
        DecimalParser.parse("0.000000000001") // scale 12, ok
        assertFailsWith<DecimalEnvelopeException> {
            DecimalParser.parse("0.0000000000001") // scale 13
        }
    }

    @Test
    fun enforcesSignificantDigitCeiling() {
        // 30 significant digits is only reachable at the envelope's corner: MAX_INT_DIGITS
        // integer digits plus MAX_SCALE fractional ones. A flat "1".repeat(30) would trip the
        // integer-digit ceiling first and so would not exercise this guard at all.
        DecimalParser.parse("1".repeat(18) + "." + "1".repeat(12)) // precision 30, ok
        val ex =
            assertFailsWith<DecimalEnvelopeException> {
                DecimalParser.parse("1".repeat(31))
            }
        assertEquals(
            "Value outside supported decimal envelope: precision 31 exceeds maximum 30",
            ex.message,
        )
    }

    @Test
    fun enforcesIntegerDigitCeiling() {
        DecimalParser.parse("999999999999999999") // 18 digits, ok
        assertFailsWith<DecimalEnvelopeException> {
            DecimalParser.parse("1000000000000000000") // 19 digits
        }
    }
}
