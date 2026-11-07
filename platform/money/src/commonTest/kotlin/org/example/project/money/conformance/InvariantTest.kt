package org.example.project.money.conformance

import kotlinx.serialization.json.Json
import org.example.project.money.Currency
import org.example.project.money.Decimal
import org.example.project.money.Money
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage
import org.example.project.money.allocate
import org.example.project.money.allocateByWeights
import org.example.project.money.sum
import org.example.project.money.vatSplitFromGross
import org.example.project.money.vatSplitFromNet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property-style invariants that must hold identically on every target. These complement the
 * golden vectors: the vectors pin exact values against the JVM oracle, these pin the structural
 * guarantees that no single vector expresses.
 */
class InvariantTest {
    private val rates = listOf("0", "7", "19", "10.7").map { Percentage.parse(it) }

    private fun eur(units: Long) = Money(Decimal.of(units, 2), Currency.EUR)

    @Test
    fun vatSplitAlwaysSumsBackToGross() {
        for (rate in rates) {
            for (cents in 1L..400L) {
                val split = eur(cents).vatSplitFromGross(rate)
                assertEquals(
                    split.gross,
                    split.net + split.vat,
                    "gross split broke at ${cents}c rate ${rate.toPercent().toPlainString()}",
                )
            }
        }
    }

    /**
     * The first gross amount at which `vat = gross - net` and `vat = net * rate` diverge.
     *
     * The other sub-cent vectors in this module (1c and 5c) happen to agree under both formulas,
     * so they cannot catch a regression to the naive one. At 3c / 19 % they do not: net rounds to
     * the full 0.03, so the correct VAT is 0.00 and the sum is 0.03, while `net * rate` gives
     * 0.03 * 0.19 = 0.0057 -> 0.01 and a sum of 0.04. The loop above covers this case as one of
     * 1,600 iterations; it is named here so the divergence is documented rather than incidental.
     */
    @Test
    fun subCentGrossSplitUsesSubtractionNotNetTimesRate() {
        val split = eur(3).vatSplitFromGross(Percentage.parse("19"))
        assertEquals("0.03", split.net.toPlainString(), "net must round up to the whole 3 cents")
        assertEquals("0.00", split.vat.toPlainString(), "vat must be gross - net, not net * rate")
        assertEquals(split.gross, split.net + split.vat, "3c @ 19 % must still sum back to gross")
        assertEquals("0.03", (split.net + split.vat).toPlainString(), "the sum must stay 0.03")
    }

    @Test
    fun vatSplitFromNetAlwaysSumsBackToGross() {
        for (rate in rates) {
            for (cents in 1L..400L) {
                val split = eur(cents).vatSplitFromNet(rate)
                assertEquals(
                    split.gross,
                    split.net + split.vat,
                    "net split broke at ${cents}c rate ${rate.toPercent().toPlainString()}",
                )
            }
        }
    }

    @Test
    fun negativeVatSplitsAlsoSumBack() {
        for (rate in rates) {
            for (cents in 1L..200L) {
                val split = eur(-cents).vatSplitFromGross(rate)
                assertEquals(split.gross, split.net + split.vat)
            }
        }
    }

    @Test
    fun equalAllocationAlwaysSumsBack() {
        for (cents in 1L..300L) {
            for (parts in 1..9) {
                val allocated = eur(cents).allocate(parts)
                assertEquals(parts, allocated.size)
                assertEquals(eur(cents), allocated.sum(), "allocate($parts) broke at ${cents}c")
            }
        }
    }

    @Test
    fun weightedAllocationAlwaysSumsBack() {
        val weightSets =
            listOf(
                listOf("1", "1", "1"),
                listOf("1", "2", "3"),
                listOf("5", "0", "5"),
                listOf("7", "11", "13", "17"),
            ).map { set -> set.map { Decimal.parse(it) } }

        for (cents in 1L..200L) {
            for (weights in weightSets) {
                val allocated = eur(cents).allocateByWeights(weights)
                assertEquals(weights.size, allocated.size)
                assertEquals(eur(cents), allocated.sum(), "weights $weights broke at ${cents}c")
            }
        }
    }

    @Test
    fun percentageFractionRoundTripIsStable() {
        for (text in listOf("0", "7", "19", "10.7", "0.5", "100")) {
            val original = Percentage.parse(text)
            val roundTripped = Percentage.ofFraction(original.toFraction())
            assertEquals(original, roundTripped, "percentage round trip broke for $text")
        }
    }

    @Test
    fun valueEqualDecimalsHashIdenticallyOnEveryTarget() {
        val pairs =
            listOf(
                "16.9" to "16.9000",
                "0" to "0.00",
                "-3.5" to "-3.500",
                "100" to "100.0000",
            )
        for ((a, b) in pairs) {
            val left = Decimal.parse(a)
            val right = Decimal.parse(b)
            assertEquals(left, right, "$a should equal $b")
            assertEquals(left.hashCode(), right.hashCode(), "$a and $b must hash identically")
        }
    }

    @Test
    fun serializationRoundTripsOnEveryTarget() {
        val json = Json
        for (text in listOf("16.90", "16.9000", "17", "0.00", "-3.50", "0.000000000001")) {
            val original = Decimal.parse(text)
            val encoded = json.encodeToString(Decimal.serializer(), original)
            assertEquals("\"$text\"", encoded, "encoding differed for $text")
            val decoded = json.decodeFromString(Decimal.serializer(), encoded)
            assertEquals(text, decoded.toPlainString(), "round trip differed for $text")
        }
    }

    @Test
    fun negativeZeroIsNeverProducedOnAnyTarget() {
        // java.math.BigDecimal drops the sign when a rounded result is zero; decimal.js keeps it.
        // Verified: BigDecimal("-0.004").setScale(2, HALF_UP) == "0.00", but
        // decimal.js new D("-0.004").toFixed(2, 4) == "-0.00".
        val roundToZero = listOf("-0.004", "-0.001", "-0.0004", "-0.0049")
        for (text in roundToZero) {
            for (mode in listOf(
                MoneyRounding.HALF_UP,
                MoneyRounding.HALF_EVEN,
                MoneyRounding.DOWN,
                MoneyRounding.CEILING,
            )) {
                val result = Decimal.parse(text).setScale(2, mode).toPlainString()
                assertEquals("0.00", result, "$text with $mode must be 0.00, not $result")
            }
        }
        // Products and quotients landing on zero with a negative operand.
        assertEquals(
            "0.00",
            (Decimal.parse("0.00") * Decimal.parse("-1"))
                .setScale(2, MoneyRounding.HALF_UP)
                .toPlainString(),
        )
        assertEquals(
            "0.00",
            Decimal.parse("0.00").divide(Decimal.parse("-5"), 2).toPlainString(),
        )
    }

    @Test
    fun setScaleHalfUpMatchesTheServerConventionOnBothSigns() {
        val cases =
            listOf(
                "2.455" to "2.46",
                "-2.455" to "-2.46",
                "0.125" to "0.13",
                "-0.125" to "-0.13",
                "16.895" to "16.90",
                "1.145" to "1.15",
            )
        for ((input, expected) in cases) {
            assertEquals(
                expected,
                Decimal.parse(input).setScale(2, MoneyRounding.HALF_UP).toPlainString(),
                "HALF_UP differed for $input",
            )
        }
    }
}
