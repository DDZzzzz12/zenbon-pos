package org.example.project.features.terminal

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Server-side integration tests for terminal lifecycle SSE notifications.
 */
class TerminalRevocationNotificationTest {
    @BeforeEach
    fun resetNotificationState() {
        NotificationHub.resetForTest()
    }

    @Test
    fun `revokeBinding emits terminal_session_terminated to bound device`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token, name = "Bar", role = "CASHIER")
            val deviceId = "dev-REVOKE-TEST"
            client.pairDevice(id, deviceId, token)

            val outgoing = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val connId =
                NotificationHub.addSseConnection(
                    connectionId = "test-conn",
                    employeeId = "admin-1",
                    deviceId = deviceId,
                    outgoing = outgoing,
                )

            try {
                client.post("/api/v1/terminals/$id/revoke") {
                    bearerAuth(token)
                }

                // Real time, not runTest's virtual clock: the frame is published by production code
                // on ordinary dispatchers, so a missing notification must fail fast rather than hang.
                val frame = withTimeout(5_000) { outgoing.receive() }
                val message = (frame as OutgoingFrame.Event).event.message
                assertTrue(message is NotificationDto.TerminalSessionTerminatedDto)
                assertEquals("REVOKED", message.reason)
                assertTrue(message.requiresRepairing)
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }

    @Test
    fun `changing a terminal's role emits ROLE_CHANGED and does not ask the device to re-pair`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // F20: the role change used to reach neither the device nor the capability cache, so a
            // terminal demoted for a reason kept running — and kept being AUTHORIZED — in its old role
            // until the app restarted.
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token, name = "Bar", role = "CASHIER")
            val deviceId = "dev-ROLE-CHANGE-TEST"
            client.pairDevice(id, deviceId, token)

            val outgoing = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val connId =
                NotificationHub.addSseConnection(
                    connectionId = "test-conn-role",
                    employeeId = "admin-1",
                    deviceId = deviceId,
                    outgoing = outgoing,
                )

            try {
                client.put("/api/v1/terminals/$id") {
                    bearerAuth(token)
                    setBody(TextContent("""{"role":"ORDER_STATION"}""", ContentType.Application.Json))
                }

                val pushed = outgoing.drainTerminalSessionFrames()
                assertEquals(1, pushed.size, "expected exactly one terminal_session_terminated push (did the PUT itself fail?)")
                val push = pushed.single()
                assertEquals("ROLE_CHANGED", push.reason)
                assertFalse(
                    push.requiresRepairing,
                    "a role change keeps the pairing — demanding a re-pair would strand the device until someone types a fresh code",
                )
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }

    @Test
    fun `an edit that does not move the role pushes nothing to the device`() =
        testApp(setupData = { TestData.createAdmin() }) { client ->
            // Both shapes of no-op: a name-only edit, and re-saving the role the terminal already has.
            // Either one evicting the device's session would log an operator out for renaming a till.
            val token = TestAuthHelper.loginAsAdmin(client)
            val id = client.provisionTerminal(token, name = "Bar", role = "CASHIER")
            val deviceId = "dev-NOOP-EDIT-TEST"
            client.pairDevice(id, deviceId, token)

            val outgoing = Channel<OutgoingFrame>(Channel.UNLIMITED)
            val connId =
                NotificationHub.addSseConnection(
                    connectionId = "test-conn-noop",
                    employeeId = "admin-1",
                    deviceId = deviceId,
                    outgoing = outgoing,
                )

            try {
                client.put("/api/v1/terminals/$id") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Tresen"}""", ContentType.Application.Json))
                }
                client.put("/api/v1/terminals/$id") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Tresen","role":"CASHIER"}""", ContentType.Application.Json))
                }

                assertEquals(
                    emptyList<String>(),
                    outgoing.drainTerminalSessionFrames().map { it.reason },
                    "a no-op edit must not terminate the device's session",
                )
            } finally {
                NotificationHub.removeConnection(connId)
            }
        }
}

/** Provisions a terminal through the real route and returns its database id. */
private suspend fun HttpClient.provisionTerminal(
    token: String,
    name: String,
    role: String,
): String =
    post("/api/v1/terminals") {
        bearerAuth(token)
        setBody(TextContent("""{"name":"$name","role":"$role"}""", ContentType.Application.Json))
    }.bodyAsText()
        .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

/** Mints a pairing code for [terminalId] and redeems it as [deviceId] would, binding the device. */
private suspend fun HttpClient.pairDevice(
    terminalId: String,
    deviceId: String,
    token: String,
) {
    val code =
        post("/api/v1/terminals/$terminalId/pairing-code") {
            bearerAuth(token)
        }.bodyAsText()
            .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }
    post("/api/v1/terminals/pair") {
        setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
    }
}

/**
 * The terminal-session-terminated pushes already queued on this channel.
 *
 * Drains rather than waits, which is what lets a test assert that NOTHING was pushed: the service
 * awaits the SSE send before the route responds, so once the HTTP call has returned, an empty
 * channel is a real absence and not a race.
 */
private fun Channel<OutgoingFrame>.drainTerminalSessionFrames(): List<NotificationDto.TerminalSessionTerminatedDto> =
    generateSequence { tryReceive().getOrNull() }
        .mapNotNull { (it as? OutgoingFrame.Event)?.event?.message as? NotificationDto.TerminalSessionTerminatedDto }
        .toList()
