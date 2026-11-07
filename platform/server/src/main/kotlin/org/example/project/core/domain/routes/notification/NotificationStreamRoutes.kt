package org.example.project.core.domain.routes.notification

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.example.project.BuildConfig
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.core.websocket.SseEvent
import org.example.project.features.terminal.domain.service.TerminalService
import org.example.project.infrastructure.plugins.getTokenClaims
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger("NotificationStreamRoutes")
private val connectionSeq = AtomicLong(0)
private val streamJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** SSE stream replacing the WebSocket notification transport. */
fun Route.notificationStreamRoutes() {
    val terminalService by inject<TerminalService>()

    authenticate("auth-jwt") {
        /**
         * Subscribe to the notification stream.
         *
         * Description: A long-lived text/event-stream carrying live notifications for the calling employee — the SSE transport that replaced the WebSocket one. Send the `Last-Event-ID` header to have events missed while disconnected replayed before the live feed starts. A till or order station also sends its client build in the `X-App-Version` header; when it differs from the software version recorded for the terminal bound to the token's device, the terminal is updated before the stream starts, so the DSFinV-K TERMINAL_SW_VERSION follows app updates. The first event on every connection is a `server_hello` carrying the server's software version. The server sends a ping comment every 15 seconds to keep the connection open, and closes the stream when the employee's sessions are revoked.
         *
         * Tag: notifications
         * Response: 401 Missing or invalid bearer token, or the token carries no usable claims.
         */
        get("/api/notifications/stream") {
            val claims = call.getTokenClaims() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val employeeId = claims.employeeId
            val deviceId = claims.deviceId
            val role = EmployeeRole.getRole(claims.role)
            val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
            val connectionId = "sse_${connectionSeq.incrementAndGet()}"
            val outgoing = Channel<OutgoingFrame>(Channel.UNLIMITED)

            // Opening the stream is the one call every till and order station makes at session start,
            // and an app update always restarts the app. Keyed on the verified claim's device id,
            // never on the raw X-Device-ID header. Best-effort: never refuses the stream.
            terminalService.recordReportedSwVersion(deviceId, call.request.headers[DeviceIdentifiers.APP_VERSION_HEADER])

            // A login-less station shares the terminal-device employee with every other station, so its
            // stream is superseded per device, not per employee — see NotificationPublisher.streamsSupersededBy.
            NotificationHub.addSseConnection(connectionId, employeeId, deviceId, outgoing, role, isStation = claims.station != null)

            logger.info("SSE stream opened: $connectionId employee=$employeeId device=$deviceId lastEventId=$lastEventId")

            try {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    // First frame of every connection: the server's version, so a till that was loaded
                    // before a server upgrade can tell its bundle is stale. Written here, ahead of the
                    // replay and of the connection's queue — which by now may already hold onConnect
                    // state (TSE status) and live broadcasts — rather than enqueued behind them.
                    // Connection-scoped and never logged, so a reconnect is not replayed an old version.
                    writeEvent(NotificationHub.connectionScopedEvent(employeeId, serverHello()))
                    NotificationHub.replayFor(employeeId, lastEventId).forEach { ev -> writeEvent(ev) }
                    flush()
                    coroutineScope {
                        val pinger =
                            launch {
                                while (isActive) {
                                    kotlinx.coroutines.delay(15_000)
                                    outgoing.trySend(OutgoingFrame.Comment("ping"))
                                }
                            }
                        try {
                            for (frame in outgoing) {
                                when (frame) {
                                    is OutgoingFrame.Event -> writeEvent(frame.event)
                                    is OutgoingFrame.Comment -> write(": ${frame.text}\n\n")
                                }
                                flush()
                            }
                        } finally {
                            pinger.cancel()
                        }
                    }
                }
            } catch (e: Exception) {
                logger.info("SSE stream closed: $connectionId (${e.message})")
            } finally {
                NotificationHub.removeConnection(connectionId)
            }
        }
    }
}

private fun serverHello(): NotificationDto.ServerHelloDto =
    NotificationDto.ServerHelloDto(timestamp = Clock.System.now().toString(), serverVersion = BuildConfig.APP_VERSION)

private fun Writer.writeEvent(event: SseEvent) {
    write("id: ${event.id}\n")
    write("data: ${streamJson.encodeToString(event.message)}\n\n")
}
