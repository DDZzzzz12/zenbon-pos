package org.example.project.core.network

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.websocket.NotificationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseEventParserTest {
    // Mirror the server's encoder (NotificationStreamRoutes): encode the SEALED type so the
    // polymorphic `type` discriminator is written exactly as the server emits it on the wire.
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `round-trips a server-encoded message and reports invalid json as an error`() {
        val original: NotificationDto.NotificationMessage =
            NotificationDto.WebSocketErrorDto(timestamp = "t", errorCode = "E", message = "hi")
        val wire = serverJson.encodeToString<NotificationDto.NotificationMessage>(original)

        val msgs = mutableListOf<NotificationDto.NotificationMessage>()
        val errs = mutableListOf<NotificationConnection.NotificationConnectionError>()

        SseEventParser.parse(wire, { msgs += it }, { errs += it })
        SseEventParser.parse("not-json", { msgs += it }, { errs += it })

        assertEquals(1, msgs.size)
        assertTrue(msgs[0] is NotificationDto.WebSocketErrorDto)
        assertEquals("hi", (msgs[0] as NotificationDto.WebSocketErrorDto).message)
        assertEquals(1, errs.size)
    }

    @Test
    fun `an unknown message type is dropped quietly - no message and no error`() {
        // What a client one release behind sees when the server gains a new event type.
        val wire = """{"type":"some_future_update","messageType":"some_future_update","timestamp":"t","x":1}"""
        val msgs = mutableListOf<NotificationDto.NotificationMessage>()
        val errs = mutableListOf<NotificationConnection.NotificationConnectionError>()

        SseEventParser.parse(wire, { msgs += it }, { errs += it })

        assertTrue(msgs.isEmpty())
        assertTrue(errs.isEmpty(), "an unknown event type must not surface as an error toast")
    }

    @Test
    fun `a known type still parses`() {
        val original: NotificationDto.NotificationMessage =
            NotificationDto.RotationNextUpdateDto(timestamp = "t", version = 7, next = null)
        val wire = serverJson.encodeToString<NotificationDto.NotificationMessage>(original)
        val msgs = mutableListOf<NotificationDto.NotificationMessage>()
        val errs = mutableListOf<NotificationConnection.NotificationConnectionError>()

        SseEventParser.parse(wire, { msgs += it }, { errs += it })

        assertEquals(listOf(original), msgs)
        assertTrue(errs.isEmpty())
    }

    @Test
    fun `a malformed message of a known type is still reported`() {
        // Known discriminator, required field `version` missing.
        val wire = """{"type":"rotation_next_update","timestamp":"t","next":null}"""
        val msgs = mutableListOf<NotificationDto.NotificationMessage>()
        val errs = mutableListOf<NotificationConnection.NotificationConnectionError>()

        SseEventParser.parse(wire, { msgs += it }, { errs += it })

        assertTrue(msgs.isEmpty())
        assertEquals(1, errs.size)
    }
}
