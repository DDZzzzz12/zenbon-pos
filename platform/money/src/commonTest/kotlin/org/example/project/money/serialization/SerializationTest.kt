package org.example.project.money.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.example.project.money.Currency
import org.example.project.money.Decimal
import org.example.project.money.Money
import org.example.project.money.Percentage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerializationTest {
    private val json = Json

    /**
     * Mirrors production: every Json instance in this project sets isLenient — pos-server's
     * ContentNegotiation and SerializationModule, shared's PosHttpClient, and all three
     * HttpClientFactory actuals. The strict default above therefore proves nothing about the
     * configuration this module actually runs under.
     */
    private val lenientJson = Json { isLenient = true }

    @Serializable
    private data class Holder(
        val price: Decimal,
    )

    @Serializable
    private data class NullableHolder(
        val price: Decimal? = null,
    )

    @Serializable
    private data class RateHolder(
        val rate: Percentage,
    )

    @Test
    fun decimalEncodesAsAJsonString() {
        assertEquals("\"16.90\"", json.encodeToString(Decimal.serializer(), Decimal.parse("16.90")))
    }

    @Test
    fun decimalPreservesScaleThroughARoundTrip() {
        for (text in listOf("16.90", "16.9000", "17", "0.00", "-3.50")) {
            val decoded =
                json.decodeFromString(
                    Decimal.serializer(),
                    json.encodeToString(Decimal.serializer(), Decimal.parse(text)),
                )
            assertEquals(text, decoded.toPlainString())
        }
    }

    @Test
    fun decimalInsideADtoMatchesTheExistingWireFormat() {
        // The server already sends money as a JSON string; this must be byte-identical.
        assertEquals("""{"price":"16.90"}""", json.encodeToString(Holder(Decimal.parse("16.90"))))
        assertEquals(Decimal.parse("16.90"), json.decodeFromString<Holder>("""{"price":"16.90"}""").price)
    }

    @Test
    fun nullableDecimalRoundTrips() {
        assertEquals("""{}""", json.encodeToString(NullableHolder()))
        assertEquals(null, json.decodeFromString<NullableHolder>("""{"price":null}""").price)
    }

    @Test
    fun jsonNumbersAreRejected() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Holder>("""{"price":16.9}""")
        }
    }

    @Test
    fun malformedStringsFailAsSerializationExceptions() {
        for (bad in listOf("1,5", "", "1e5", "NaN", "abc")) {
            assertFailsWith<SerializationException>("expected rejection of \"$bad\"") {
                json.decodeFromString<Holder>("""{"price":"$bad"}""")
            }
        }
    }

    @Test
    fun envelopeIsEnforcedOnDecode() {
        val tooWide = "1".repeat(31)
        assertFailsWith<SerializationException> {
            json.decodeFromString<Holder>("""{"price":"$tooWide"}""")
        }
    }

    @Test
    fun currencyEncodesAsItsCode() {
        assertEquals("\"EUR\"", json.encodeToString(Currency.serializer(), Currency.EUR))
        assertEquals(Currency.EUR, json.decodeFromString(Currency.serializer(), "\"EUR\""))
    }

    @Test
    fun invalidCurrencyCodeFailsAsSerializationException() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(Currency.serializer(), "\"EU\"")
        }
    }

    @Test
    fun moneyEncodesAsAnObject() {
        val money = Money(Decimal.parse("16.90"), Currency.EUR)
        assertEquals("""{"amount":"16.90","currency":"EUR"}""", json.encodeToString(money))
        assertEquals(money, json.decodeFromString<Money>("""{"amount":"16.90","currency":"EUR"}"""))
    }

    @Test
    fun percentageEncodesAsPercentUnits() {
        assertEquals("\"19\"", json.encodeToString(Percentage.serializer(), Percentage.parse("19")))
        assertEquals(
            Percentage.parse("19.00"),
            json.decodeFromString(Percentage.serializer(), "\"19.00\""),
        )
    }

    @Test
    fun decimalJsonNumbersAreRejectedUnderLenientJsonToo() {
        // decodeString() alone returns an unquoted number token verbatim when isLenient is on,
        // so "16.9" would decode silently instead of failing loudly.
        assertFailsWith<SerializationException>("expected rejection of the number 16.9") {
            lenientJson.decodeFromString<Holder>("""{"price":16.9}""")
        }
        assertFailsWith<SerializationException>("expected rejection of the number 17") {
            lenientJson.decodeFromString<Holder>("""{"price":17}""")
        }
    }

    @Test
    fun percentageJsonNumbersAreRejectedUnderLenientJsonToo() {
        assertFailsWith<SerializationException>("expected rejection of the number 19") {
            lenientJson.decodeFromString<RateHolder>("""{"rate":19}""")
        }
    }

    @Test
    fun lenientJsonStillDecodesLegitimateStringPayloads() {
        // The guard must reject non-strings only; it must not break lenient parsing as such.
        assertEquals(
            "16.90",
            lenientJson.decodeFromString<Holder>("""{"price":"16.90"}""").price.toPlainString(),
        )
        assertEquals(
            Percentage.parse("19"),
            lenientJson.decodeFromString<RateHolder>("""{"rate":"19"}""").rate,
        )
        // Unquoted keys are a lenient-mode feature and must keep working.
        assertEquals(
            "16.90",
            lenientJson.decodeFromString<Holder>("""{price:"16.90"}""").price.toPlainString(),
        )
        assertEquals(null, lenientJson.decodeFromString<NullableHolder>("""{"price":null}""").price)
    }
}
