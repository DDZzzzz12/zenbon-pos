package org.example.project.core.domain.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * A `String` serializer for money-on-the-wire fields that **rejects a JSON number**.
 *
 * The repo-wide request `Json` is `isLenient = true`, under which a bare `decodeString()` silently
 * coerces `25.00` (a JSON number) into the string `"25.00"`. That defeats the project's hard rule
 * that money crosses the wire as a JSON *string*, never a number (see `platform/money/README.md` and the root
 * `CLAUDE.md`) — a client could send `0.1` and have it enter the system as an IEEE-754 double before
 * anyone parses it. Catching it needs `JsonDecoder` inspection, which is exactly what this does:
 * the underlying `JsonPrimitive` must be a quoted string (`isString`), or deserialization fails.
 *
 * Encoding is unchanged — the value is written as a plain string, so a client that already sends a
 * string is unaffected. Apply with `@Serializable(with = MoneyStringSerializer::class)` on inbound
 * money fields. A non-JSON decoder (never the case for an HTTP body) falls back to `decodeString()`.
 */
object MoneyStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MoneyString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        val primitive =
            element as? JsonPrimitive
                ?: throw SerializationException("money must be a JSON string, not ${element::class.simpleName}")
        if (!primitive.isString) {
            throw SerializationException(
                "money must be a JSON string, not a bare number: ${primitive.content}",
            )
        }
        return primitive.content
    }
}
