package org.example.project.money.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a value that must have arrived as a JSON *string*, rejecting JSON numbers outright.
 *
 * [Decoder.decodeString] alone is not enough. It is strict only under a default [kotlinx.serialization.json.Json];
 * with `isLenient = true` an unquoted number token is returned verbatim, so `{"price":16.9}` would
 * decode silently. Every Json instance in this project is lenient — pos-server's ContentNegotiation
 * and SerializationModule, shared's PosHttpClient, and all three HttpClientFactory actuals — so the
 * strict-Json behaviour is not the behaviour money is actually decoded under.
 *
 * A JSON number is exactly the shape a Double-based producer emits, and accepting it would let the
 * precision defect this module exists to remove back in through the untrusted-input path. It must
 * fail loudly instead.
 *
 * The [JsonDecoder] branch is taken only when the format is JSON; every other format falls through
 * to [Decoder.decodeString], so serializers built on this stay format-agnostic.
 */
internal object StrictStringDecoding {
    fun decode(
        decoder: Decoder,
        typeName: String,
    ): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()

        val element = decoder.decodeJsonElement()
        if (element !is JsonPrimitive || !element.isString) {
            throw SerializationException(
                "$typeName must be a JSON string, but was $element. JSON numbers are rejected " +
                    "because many parsers decode them through Double, which loses both scale and value.",
            )
        }
        return element.content
    }
}
