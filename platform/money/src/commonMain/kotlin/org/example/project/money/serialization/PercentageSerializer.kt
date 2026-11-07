package org.example.project.money.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.example.project.money.MoneyException
import org.example.project.money.Percentage

/** Encodes as a percent-unit string, e.g. "19" for 19 % — matching VatRateNormalizer output. */
object PercentageSerializer : KSerializer<Percentage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.example.project.money.Percentage", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Percentage,
    ) {
        encoder.encodeString(value.toPercent().toPlainString())
    }

    override fun deserialize(decoder: Decoder): Percentage {
        val text = StrictStringDecoding.decode(decoder, "Percentage")
        return try {
            Percentage.parse(text)
        } catch (e: MoneyException) {
            throw SerializationException("Invalid percentage \"$text\"", e)
        }
    }
}
