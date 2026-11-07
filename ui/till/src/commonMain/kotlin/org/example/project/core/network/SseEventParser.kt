package org.example.project.core.network

import co.touchlab.kermit.Logger
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.example.project.core.domain.dto.websocket.NotificationDto

/**
 * Stateless SSE `data:` payload parser — isolated so it is unit-testable and reusable.
 *
 * Decodes polymorphically through the sealed [NotificationDto.NotificationMessage] serializer,
 * exactly mirroring how the server encodes events (`encodeToString(message)` on the sealed
 * type, which writes a `type` class discriminator). Using the same sealed serializer on both
 * ends means new message subtypes round-trip automatically — there is no hand-maintained
 * type registry to drift out of sync (the silent-parse-failure mode this rewrite removes).
 *
 * An event whose `type` this build does not know is dropped with a log line and NO error: it is
 * what every client one release behind sees when the server gains an event type, and reporting it
 * put a warning toast on every till for an event it had no use for. A known type that fails to
 * decode is still reported.
 */
object SseEventParser {
    private const val DISCRIMINATOR = "type"

    private val logger = Logger.withTag("SseEventParser")

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Serial names of every subtype this build can decode (the sealed descriptor's `value` element). */
    private val knownTypes: Set<String> =
        serializer<NotificationDto.NotificationMessage>()
            .descriptor
            .getElementDescriptor(1)
            .elementNames
            .toSet()

    fun parse(
        data: String,
        onMessage: (NotificationDto.NotificationMessage) -> Unit,
        onError: (NotificationConnection.NotificationConnectionError) -> Unit,
    ) {
        val type = discriminatorOf(data)
        if (type != null && type !in knownTypes) {
            logger.i { "Ignoring SSE event of unknown type '$type' (sent by a newer server)" }
            return
        }
        try {
            onMessage(json.decodeFromString<NotificationDto.NotificationMessage>(data))
        } catch (e: Exception) {
            onError(NotificationConnection.NotificationConnectionError.DeserializationFailed(e.message ?: "parse error"))
        }
    }

    /** The `type` discriminator, or null when [data] is not a JSON object carrying a string one. */
    private fun discriminatorOf(data: String): String? =
        runCatching {
            (json.parseToJsonElement(data) as? JsonObject)?.get(DISCRIMINATOR)?.jsonPrimitive?.contentOrNull
        }.getOrNull()
}
