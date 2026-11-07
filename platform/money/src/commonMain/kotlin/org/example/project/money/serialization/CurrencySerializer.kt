package org.example.project.money.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.example.project.money.Currency
import org.example.project.money.MoneyException

/** Encodes as the ISO-4217 code, e.g. "EUR". */
object CurrencySerializer : KSerializer<Currency> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.example.project.money.Currency", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Currency,
    ) {
        encoder.encodeString(value.code)
    }

    override fun deserialize(decoder: Decoder): Currency {
        val code = decoder.decodeString()
        return try {
            Currency.parse(code)
        } catch (e: MoneyException) {
            throw SerializationException("Invalid currency code \"$code\"", e)
        }
    }
}
