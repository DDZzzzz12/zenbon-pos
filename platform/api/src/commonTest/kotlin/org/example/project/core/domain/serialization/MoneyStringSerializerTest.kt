package org.example.project.core.domain.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyStringSerializerTest {
    @Serializable
    private data class Holder(
        @Serializable(with = MoneyStringSerializer::class) val amount: String,
    )

    // The exact configuration the server uses to parse request bodies.
    private val lenient =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun acceptsAQuotedMoneyString() {
        val h = lenient.decodeFromString<Holder>("""{"amount":"25.00"}""")
        assertEquals("25.00", h.amount)
    }

    @Test
    fun rejectsABareJsonNumberEvenUnderIsLenient() {
        // This is D7: without the serializer, isLenient coerces 25.00 -> "25.00" silently.
        assertFailsWith<SerializationException> {
            lenient.decodeFromString<Holder>("""{"amount":25.00}""")
        }
    }

    @Test
    fun rejectsAnIntegerJsonNumber() {
        assertFailsWith<SerializationException> {
            lenient.decodeFromString<Holder>("""{"amount":25}""")
        }
    }

    @Test
    fun encodesAsAPlainString() {
        val json = lenient.encodeToString(Holder(amount = "25.00"))
        assertEquals("""{"amount":"25.00"}""", json)
    }
}
