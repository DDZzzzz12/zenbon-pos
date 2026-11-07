package org.example.project.money.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.example.project.money.Decimal
import org.example.project.money.MoneyException

/**
 * Encodes as a JSON string, never a JSON number.
 *
 * JSON numbers are decoded through Double by many parsers, which would reintroduce the precision
 * defect this module exists to remove. Existing DTOs already carry money as strings, so this is
 * byte-identical to the wire format the server already emits and accepts.
 */
object DecimalSerializer : KSerializer<Decimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.example.project.money.Decimal", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Decimal,
    ) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): Decimal {
        val text = StrictStringDecoding.decode(decoder, "Decimal")
        return try {
            Decimal.parse(text)
        } catch (e: MoneyException) {
            throw SerializationException("Invalid decimal \"$text\"", e)
        }
    }
}
